#!/usr/bin/env python3
"""Validate native upload/summary/trace wiring over a disposable full Go PostgreSQL schema."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='paradedb/paradedb:v0.22.6-pg17')
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-processing-'))
    container = None

    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)

    try:
        container = command([args.docker, 'run', '-d', '--rm', '--name', 'weknora-java-processing-'+uuid.uuid4().hex[:12],
            '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD=local-parity-only',
            '-e', 'POSTGRES_DB=weknora_processing_parity', args.image], capture_output=True, text=True).stdout.strip()
        deadline = time.monotonic()+45
        while subprocess.run([args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1',
                '-U', 'postgres', '-d', 'weknora_processing_parity'], stdout=subprocess.DEVNULL,
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
                    '-U', 'postgres', '-d', 'weknora_processing_parity'], input=migration.read_bytes(),
                    stdout=log, stderr=subprocess.STDOUT)
        with (output/'java.log').open('wb') as log:
            command(['mvn', '-q', '-f', 'backend-java/pom.xml', '-Dweknora.build.directory='+str(output/'build'),
                '-Dweknora.parity.processing.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_processing_parity',
                '-Dtest=PostgresProcessingPipelineIT', 'clean', 'test'], stdout=log, stderr=subprocess.STDOUT)
        print(json.dumps({'uploads': 10, 'jobs': 48, 'successful_jobs': 46, 'canceled_jobs': 2, 'embedding_http': 38, 'chat_http': 27,
            'go_schema_migrations': len(migrations)-1, 'database': 'PostgreSQL/ParadeDB',
            'pool_size': 1, 'bridge': False,
            'checks': 'native own/shared multipart upload, halfvec publication, summary/profile/question metadata JSONB, combined summary/question/tag fanout/counters, debounced knowledge base profile JSONB with own/shared synchronous regeneration, cancellation during real model HTTP with Wiki scrub and retained halfvec, public own creator-viewer/shared contributor metadata PUT -> native durable refresh task -> actual model -> retained summary chunk ID, new halfvec with retained source text/index/quota/counters; public own creator-viewer/shared contributor chunk edit/revert immutable histories and stale/no-op, actual BM25/halfvec replacement and native summary refresh with source/quota retained; public own/shared chunk question PUT/DELETE and twice synchronous regenerate with hashed source replacement/BM25/halfvec and no new revision/task/source/quota changes; public own/shared folder tree/creator-viewer rename and bulk refile retain actual indexes/source/quota/no new tasks; public chunk revisions/own creator-viewer single/shared editor bulk tombstones retain actual ParadeDB BM25/halfvec rows and query results/source bytes/quota/no new tasks, scoped index:delete rejects wrong KB and removes indexes for soft-deleted own/shared chunks with captured model and low queue, public tag force/content_only -> owner-scoped knowledge:list_delete with hard/retained tags, real physical-file and halfvec removal, quota decrement and initiating actor audit, spans/stages/index-status HTTP',
            'artifacts': str(output)}))
    finally:
        if container:
            subprocess.run([args.docker, 'stop', container], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, check=False)
        print('Processing artifacts: '+str(output))


if __name__ == '__main__':
    main()
