#!/usr/bin/env python3
"""Replay real Go document collection HTTP on a runner-owned PostgreSQL database."""
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
    parser.add_argument('--planner-state', choices=['unanalysed', 'analysed'], default='unanalysed')
    args = parser.parse_args()
    root = args.root.resolve()
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-collection-read-'))
    container = None

    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)

    try:
        container = command([
            args.docker, 'run', '-d', '--rm', '--name',
            'weknora-java-collection-read-' + uuid.uuid4().hex[:12],
            '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD=local-parity-only',
            '-e', 'POSTGRES_DB=weknora_file_parity', args.image, '-c', 'autovacuum=off',
        ], capture_output=True, text=True).stdout.strip()
        deadline = time.monotonic() + 60
        # Require TCP readiness so the temporary init socket server cannot satisfy readiness.
        while subprocess.run([
            args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1',
            '-U', 'postgres', '-d', 'weknora_file_parity',
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline:
                raise RuntimeError('Owned PostgreSQL startup deadline')
            time.sleep(.2)
        port = command([args.docker, 'port', container, '5432'], capture_output=True,
                       text=True).stdout.strip().rsplit(':', 1)[1]
        migrations = sorted((root / 'migrations/versioned').glob('*.up.sql'))
        with (output / 'schema.log').open('wb') as log:
            for migration in migrations + [root / 'backend-java/migration/sql/java-tasks.sql']:
                log.write((str(migration) + '\n').encode())
                log.flush()
                command([args.docker, 'exec', '-i', container, 'psql', '-v',
                         'ON_ERROR_STOP=1', '-U', 'postgres', '-d',
                         'weknora_file_parity'], input=migration.read_bytes(),
                        stdout=log, stderr=subprocess.STDOUT)
        env = os.environ.copy()
        env['WEKNORA_COLLECTION_PARITY_ANALYZE'] = '1' if args.planner_state == 'analysed' else '0'
        env['WEKNORA_COLLECTION_PARITY_PG_DSN'] = (
            f'postgres://postgres:local-parity-only@127.0.0.1:{port}/'
            'weknora_file_parity?sslmode=disable')
        mode = 'http'
        fixture = output / ('go-collection-read-' + mode + '-pg.json')
        with (output / 'go.log').open('wb') as log:
            command(['go', 'run', '-tags', 'sqlite_fts5',
                     'backend-java/src/test/fixtures/knowledge_collection_read_http_fixture.go',
                     str(fixture)], env=env, stdout=log, stderr=subprocess.STDOUT)
        rows = json.loads(fixture.read_text())
        assert len(rows) == len(json.loads((root / ('backend-java/src/test/resources/go-knowledge-collection-read-' + mode + '.json')).read_text()))
        count = sum(not row.get('pg_unsupported_invalid_json') and not row.get('pg_unsupported_nul_text') for row in rows)
        with (output / 'java.log').open('wb') as log:
            command(['mvn', '-q', '-f', 'backend-java/pom.xml',
                     '-Dweknora.build.directory=' + str(output / 'build'),
                     '-Dweknora.collection.read.pg.url=jdbc:postgresql://127.0.0.1:' +
                     port + '/weknora_file_parity',
                     '-Dweknora.collection.read.pg.fixture=' + str(fixture),
                     '-Dtest=' + 'KnowledgeCollectionReadPostgresGoParityIT', 'clean', 'test'],
                    env=env, stdout=log, stderr=subprocess.STDOUT)
        print(json.dumps({'mode': mode, 'planner_state': args.planner_state, 'cases': count, 'go_migrations': len(migrations),
                          'pool_size': 1, 'artifacts': str(output)}))
    finally:
        if container:
            subprocess.run([args.docker, 'stop', container], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
        print('COLLECTION READ parity artifacts: ' + str(output))


if __name__ == '__main__':
    main()
