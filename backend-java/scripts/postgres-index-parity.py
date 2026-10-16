#!/usr/bin/env python3
"""Replay production Go and Java index repositories against disposable ParadeDB."""
import argparse
import json
import os
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
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-index-parity-'))
    container = None

    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)

    try:
        container = command([args.docker, 'run', '-d', '--rm', '--name', 'weknora-java-parity-'+uuid.uuid4().hex[:12],
            '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD=local-parity-only',
            '-e', 'POSTGRES_DB=weknora_parity', args.image], capture_output=True, text=True).stdout.strip()
        deadline = time.monotonic()+45
        # The image briefly runs a Unix-only bootstrap server. Wait for final TCP startup.
        while subprocess.run([args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'weknora_parity'],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline:
                raise RuntimeError('Isolated ParadeDB did not become ready')
            time.sleep(.2)
        port = command([args.docker, 'port', container, '5432'], capture_output=True, text=True).stdout.strip().rsplit(':', 1)[1]
        with (output/'schema.log').open('wb') as log:
            for migration in ['000002_embeddings.up.sql', '000007_embeddings_tag_id.up.sql']:
                command([args.docker, 'exec', '-i', container, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'weknora_parity'],
                    input=(root/'migrations/versioned'/migration).read_bytes(), stdout=log, stderr=subprocess.STDOUT)
        env = os.environ.copy()
        env['WEKNORA_PARITY_PG_DSN'] = f'host=127.0.0.1 port={port} user=postgres password=local-parity-only dbname=weknora_parity sslmode=disable'
        fixture = output/'go-postgres-index.json'
        with (output/'go.log').open('wb') as log:
            command(['go', 'run', 'backend-java/src/test/fixtures/postgres_index_fixture.go', str(fixture)], env=env, stdout=log, stderr=subprocess.STDOUT)
        data = json.loads(fixture.read_text())
        assert len(data['steps']) == 16
        assert sum(len(step['queries']) for step in data['steps']) == 2368
        with (output/'java.log').open('wb') as log:
            command(['mvn', '-q', '-f', 'backend-java/pom.xml', '-Dweknora.build.directory='+str(output/'build'),
                '-Dweknora.parity.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_parity',
                '-Dweknora.parity.pg.fixture='+str(fixture), '-Dtest=PostgresIndexGoParityIT', 'clean', 'test'], stdout=log, stderr=subprocess.STDOUT)
        print(json.dumps({'mutations': 16, 'retrieval_queries': 2368, 'image': args.image,
            'checks': 'production Go repository vs Java over actual halfvec/BM25; full rows/results/SQLSTATE; one connection pool',
            'artifacts': str(output)}))
    finally:
        if container:
            subprocess.run([args.docker, 'stop', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        print('Parity artifacts: '+str(output))


if __name__ == '__main__':
    main()
