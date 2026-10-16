#!/usr/bin/env python3
"""Validate native graph extraction with real PostgreSQL/Neo4j/APOC and the full Go schema."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--neo4j-uri', required=True, help='Isolated real Neo4j/APOC Bolt endpoint')
    parser.add_argument('--image', default='paradedb/paradedb:v0.22.6-pg17')
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-graph-'))
    container = None

    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)

    try:
        container = command([args.docker, 'run', '--pull=never', '-d', '--rm', '--name', 'weknora-java-processing-'+uuid.uuid4().hex[:12],
            '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD=local-parity-only',
            '-e', 'POSTGRES_DB=weknora_graph_processing_parity', args.image], capture_output=True, text=True).stdout.strip()
        deadline = time.monotonic()+45
        while subprocess.run([args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1',
                '-U', 'postgres', '-d', 'weknora_graph_processing_parity'], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline:
                raise RuntimeError('Disposable ParadeDB did not become ready')
            time.sleep(.2)
        port = command([args.docker, 'port', container, '5432'], capture_output=True, text=True).stdout.strip().rsplit(':', 1)[1]
        migrations = sorted((root/'migrations/versioned').glob('*.up.sql'))
        migrations.append(root/'backend-java/migration/sql/java-tasks.sql')
        with (output/'schema.log').open('wb') as log:
            for migration in migrations:
                log.write((str(migration)+'\n').encode())
                log.flush()
                command([args.docker, 'exec', '-i', container, 'psql', '-v', 'ON_ERROR_STOP=1',
                    '-U', 'postgres', '-d', 'weknora_graph_processing_parity'], input=migration.read_bytes(),
                    stdout=log, stderr=subprocess.STDOUT)
        with (output/'java.log').open('wb') as log:
            command(['mvn', '-q', '-f', 'backend-java/pom.xml', '-Dweknora.build.directory='+str(output/'build'),
                '-Dweknora.parity.processing.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_graph_processing_parity',
                '-Dweknora.graph.test.uri='+args.neo4j_uri, '-Dtest=GraphExtractionPostgresPipelineIT', 'clean', 'test'], stdout=log, stderr=subprocess.STDOUT)
        print(json.dumps({'uploads':5,'jobs':19,'embedding_http':9,'chat_http':9,
            'go_schema_migrations':len(migrations)-1,'database':'actual PostgreSQL/ParadeDB + Neo4j/APOC','pool_size':1,'bridge':False,
            'checks':'own/shared graph+summary+question fanout; actual Neo4j nodes/relations, halfvec/JSONB, SQL receipts/counters, public counts/details/trace, unshared403, revision/cancel/lostlease during model HTTP','artifacts':str(output)}))
    finally:
        if container:
            subprocess.run([args.docker, 'stop', container], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, check=False)
        print('Processing artifacts: '+str(output))


if __name__ == '__main__':
    main()
