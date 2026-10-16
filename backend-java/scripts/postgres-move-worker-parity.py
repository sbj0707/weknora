#!/usr/bin/env python3
"""Compare the actual Go move worker and named queue with Java on an owned PostgreSQL schema."""
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
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-move-worker-'))
    state = dict(status='starting', root=str(root), artifacts=str(output))
    container = None

    def save():
        (output / 'manifest.json').write_text(json.dumps(state, indent=2) + '\n')

    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)

    try:
        save()
        container = command([args.docker, 'run', '-d', '--rm', '--name',
            'weknora-java-move-worker-' + uuid.uuid4().hex[:12], '-p', '127.0.0.1::5432',
            '-e', 'POSTGRES_PASSWORD=local-parity-only', '-e', 'POSTGRES_DB=weknora_move_plan_parity',
            args.image], capture_output=True, text=True).stdout.strip()
        state['container'] = container
        save()
        deadline = time.monotonic() + 45
        while subprocess.run([args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1',
            '-U', 'postgres', '-d', 'weknora_move_plan_parity'], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline:
                raise RuntimeError('Owned PostgreSQL startup deadline')
            time.sleep(.2)
        port = command([args.docker, 'port', container, '5432'], capture_output=True,
            text=True).stdout.strip().rsplit(':', 1)[1]
        migrations = sorted((root / 'migrations/versioned').glob('*.up.sql'))
        with (output / 'schema.log').open('wb') as log:
            for migration in migrations + [root / 'backend-java/migration/sql/java-tasks.sql']:
                log.write((str(migration) + '\n').encode()); log.flush()
                command([args.docker, 'exec', '-i', container, 'psql', '-v', 'ON_ERROR_STOP=1',
                    '-U', 'postgres', '-d', 'weknora_move_plan_parity'],
                    input=migration.read_bytes(), stdout=log, stderr=subprocess.STDOUT)
        state.update(status='exporting_go', go_migrations=len(migrations))
        save()
        env = os.environ.copy()
        env['WEKNORA_MOVE_PLAN_PG_DSN'] = 'postgres://postgres:local-parity-only@127.0.0.1:' + port + '/weknora_move_plan_parity?sslmode=disable'
        fixture = output / 'go-move-worker-pg.json'
        with (output / 'go.log').open('wb') as log:
            command(['python3', 'backend-java/scripts/export-move-worker-oracle.py', str(fixture)],
                env=env, stdout=log, stderr=subprocess.STDOUT)
        assert len(json.loads(fixture.read_text())) == 46
        state.update(status='testing_java', go_cases=46)
        save()
        cmd = ['mvn', '-q', '-f', 'backend-java/pom.xml', '-Dweknora.build.directory=' + str(output / 'build'),
            '-Dweknora.move.worker.pg.url=jdbc:postgresql://127.0.0.1:' + port + '/weknora_move_plan_parity',
            '-Dweknora.move.worker.fixture=' + str(fixture),
            '-Dweknora.move.named.fixture=' + str(fixture.with_name(fixture.stem + '-named.json')),
            '-Dtest=KnowledgeMoveWorkerPostgresGoParityIT,NamedTaskProducerPostgresGoParityIT', 'clean', 'test']
        state['command'] = cmd
        save()
        with (output / 'java.log').open('wb') as log:
            result = subprocess.run(cmd, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        state.update(status='complete', actual_exit_code=result.returncode, worker_pool_size=1, named_queue_pool_size=8)
        save()
        if result.returncode:
            raise RuntimeError('PostgreSQL differential tests failed; inspect ' + str(output / 'java.log'))
        print(json.dumps(state))
    finally:
        if container:
            state['container_stop_exit'] = subprocess.run([args.docker, 'stop', container],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        save()
        print('Move-worker parity artifacts: ' + str(output))


if __name__ == '__main__':
    main()
