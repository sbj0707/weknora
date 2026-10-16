#!/usr/bin/env python3
"""Compare native settings duplication with actual Go over an owned full-schema PostgreSQL database."""
import argparse, hashlib, json, os, subprocess, tempfile, time, uuid
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--image', default='paradedb/paradedb:v0.22.6-pg17')
    p.add_argument('--docker', default='docker')
    p.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = p.parse_args()
    root = args.root.resolve()
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-duplicates-'))
    state = {'status': 'starting', 'artifacts': str(output), 'root': str(root)}
    container = None

    def save():
        (output / 'manifest.json').write_text(json.dumps(state, indent=2) + '\n')

    def command(values, **kwargs):
        result = subprocess.run(values, cwd=root, **kwargs)
        result.check_returncode()
        return result

    print('Duplicate parity artifacts: ' + str(output), flush=True)
    try:
        save()
        container = command([args.docker, 'run', '-d', '--rm', '--name', 'weknora-java-duplicates-' + uuid.uuid4().hex[:12], '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD=local-parity-only', '-e', 'POSTGRES_DB=weknora_duplicate_parity', args.image], capture_output=True, text=True).stdout.strip()
        state['container'] = container
        save()
        deadline = time.monotonic() + 45
        while subprocess.run([args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'weknora_duplicate_parity'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline:
                raise RuntimeError('Owned PostgreSQL startup deadline')
            time.sleep(.2)
        port = command([args.docker, 'port', container, '5432'], capture_output=True, text=True).stdout.strip().rsplit(':', 1)[1]
        migrations = sorted((root / 'migrations/versioned').glob('*.up.sql'))
        with (output / 'schema.log').open('wb') as log:
            for migration in migrations + [root / 'backend-java/migration/sql/java-tasks.sql']:
                log.write((str(migration) + '\n').encode()); log.flush()
                command([args.docker, 'exec', '-i', container, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'weknora_duplicate_parity'], input=migration.read_bytes(), stdout=log, stderr=subprocess.STDOUT)
        state['go_migrations'] = len(migrations)
        env = os.environ.copy()
        env['WEKNORA_DUPLICATE_PG_DSN'] = f'postgres://postgres:local-parity-only@127.0.0.1:{port}/weknora_duplicate_parity?sslmode=disable'
        fixture = output / 'go-duplicates-pg.json'
        with (output / 'go.log').open('wb') as log:
            command(['go', 'run', '-tags', 'sqlite_fts5', 'backend-java/src/test/fixtures/knowledge_duplicate_fixture.go', str(fixture)], env=env, stdout=log, stderr=subprocess.STDOUT)
        state['go_cases'] = len(json.loads(fixture.read_text()))
        assert state['go_cases'] == 45
        state['fixture_sha256'] = hashlib.sha256(fixture.read_bytes()).hexdigest()
        values = ['mvn', '-q', '-f', 'backend-java/pom.xml', '-Dweknora.build.directory=' + str(output / 'build'), '-Dweknora.duplicate.pg.url=jdbc:postgresql://127.0.0.1:' + port + '/weknora_duplicate_parity', '-Dweknora.duplicate.pg.fixture=' + str(fixture), '-Dtest=KnowledgeBaseDuplicatePostgresGoParityIT', 'clean', 'test']
        state.update(status='running', command=values, pool_size=1)
        save()
        with (output / 'java.log').open('wb') as log:
            result = subprocess.run(values, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        state['actual_exit_code'] = result.returncode
        result.check_returncode()
        state['status'] = 'complete'
    except BaseException as error:
        state.update(status='failed', error=str(error))
        raise
    finally:
        if container:
            state['container_stop_exit'] = subprocess.run([args.docker, 'stop', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        save()
        print(json.dumps(state), flush=True)


if __name__ == '__main__':
    main()
