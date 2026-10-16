#!/usr/bin/env python3
"""Replay actual Go CloneChunk, image deep-copy and cleanup in an owned PostgreSQL DB."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='paradedb/paradedb:v0.22.6-pg17')
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    output = Path(tempfile.mkdtemp(prefix='weknora-postgres-clone-chunks-'))
    state = dict(status='running', artifacts=str(output), root=str(root), pool_size=1)
    container = None

    def save():
        (output / 'manifest.json').write_text(json.dumps(state, indent=2) + '\n')

    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)

    save()
    try:
        container = command([
            args.docker, 'run', '-d', '--rm', '--name',
            'weknora-java-clone-chunks-' + uuid.uuid4().hex[:12], '-p', '127.0.0.1::5432',
            '-e', 'POSTGRES_PASSWORD=local-parity-only',
            '-e', 'POSTGRES_DB=weknora_clone_chunks_parity', args.image
        ], capture_output=True, text=True).stdout.strip()
        state['container'] = container
        save()
        deadline = time.monotonic() + 45
        while subprocess.run([
            args.docker, 'exec', container, 'pg_isready', '-h', '127.0.0.1',
            '-U', 'postgres', '-d', 'weknora_clone_chunks_parity'
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            if time.monotonic() > deadline:
                raise RuntimeError('Owned PG startup deadline')
            time.sleep(.2)
        port = command([args.docker, 'port', container, '5432'],
                       capture_output=True, text=True).stdout.strip().rsplit(':', 1)[1]
        migrations = sorted((root / 'migrations/versioned').glob('*.up.sql'))
        with (output / 'schema.log').open('wb') as log:
            for migration in migrations + [root / 'backend-java/migration/sql/java-tasks.sql']:
                log.write((str(migration) + '\n').encode())
                log.flush()
                command([args.docker, 'exec', '-i', container, 'psql', '-v',
                         'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'weknora_clone_chunks_parity'],
                        input=migration.read_bytes(), stdout=log, stderr=subprocess.STDOUT)
        state['go_migrations'] = len(migrations)
        env = os.environ.copy()
        env['WEKNORA_CLONE_CHUNKS_PG_DSN'] = (
            f'postgres://postgres:local-parity-only@127.0.0.1:{port}'
            '/weknora_clone_chunks_parity?sslmode=disable')
        fixture = output / 'go-clone-chunks-pg.json'
        with (output / 'go.log').open('wb') as log:
            command(['python3', 'backend-java/scripts/export-clone-chunks-oracle.py', str(fixture)],
                    env=env, stdout=log, stderr=subprocess.STDOUT)
        rows = json.loads(fixture.read_text())
        assert len(rows) == 68
        state.update(go_cases=len(rows), fixture_sha256=hashlib.sha256(fixture.read_bytes()).hexdigest())
        values = ['mvn', '-q', '-f', 'backend-java/pom.xml',
                  '-Dweknora.build.directory=' + str(output / 'build'),
                  '-Dweknora.clone.chunks.pg.url=jdbc:postgresql://127.0.0.1:' + port + '/weknora_clone_chunks_parity',
                  '-Dweknora.clone.chunks.fixture=' + str(fixture),
                  '-Dtest=KnowledgeCloneChunksPostgresGoParityIT', 'clean', 'test']
        state['command'] = values
        save()
        with (output / 'java.log').open('wb') as log:
            result = subprocess.run(values, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        state['actual_exit_code'] = result.returncode
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        reports = sorted((output / 'build/surefire-reports').glob('TEST-*.xml'))
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, 0))
        state['tests'] = dict(classes=len(reports), **counts)
        state['report_hashes'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in reports}
        state['status'] = 'complete' if result.returncode == 0 else 'failed'
        save()
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, values)
        assert counts == dict(tests=68, failures=0, errors=0, skipped=0)
        print(json.dumps(state))
    except BaseException as error:
        state.update(status='failed', error=str(error))
        if isinstance(error, subprocess.CalledProcessError):
            state['failed_command_exit'] = error.returncode
            for key in ('stdout', 'stderr'):
                value = getattr(error, key, None)
                if value:
                    state['failed_command_' + key] = value.decode(errors='replace') if isinstance(value, bytes) else value
        save()
        raise
    finally:
        if container:
            stopped = subprocess.run([args.docker, 'stop', container],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            state['container_stop_exit'] = stopped.returncode
        save()
        print('Clone-chunks parity artifacts: ' + str(output))


if __name__ == '__main__':
    main()
