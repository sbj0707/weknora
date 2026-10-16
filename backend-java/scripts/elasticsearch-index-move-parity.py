#!/usr/bin/env python3
"""Run actual Go and native Java physical index move parity on an owned Elasticsearch 8."""
import argparse, json, os, subprocess, tempfile, time, urllib.request, uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='docker.elastic.co/elasticsearch/elasticsearch:8.14.0')
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args(); root = args.root.resolve()
    output = Path(tempfile.mkdtemp(prefix='weknora-es8-index-move-')); container = None
    def command(values, **kwargs):
        return subprocess.run(values, cwd=root, check=True, **kwargs)
    try:
        container = command([args.docker, 'run', '-d', '--rm', '--name', 'weknora-es8-index-move-' + uuid.uuid4().hex[:12],
                             '-p', '127.0.0.1::9200', '-e', 'discovery.type=single-node', '-e', 'xpack.security.enabled=false',
                             '-e', 'ES_JAVA_OPTS=-Xms512m -Xmx512m', args.image], capture_output=True, text=True).stdout.strip()
        port = command([args.docker, 'port', container, '9200'], capture_output=True, text=True).stdout.strip().rsplit(':', 1)[1]
        address = 'http://127.0.0.1:' + port
        deadline = time.monotonic() + 120
        while True:
            try:
                with urllib.request.urlopen(address + '/_cluster/health?wait_for_status=yellow&timeout=1s', timeout=2) as response:
                    if json.load(response)['status'] in ('yellow', 'green'): break
            except (OSError, ValueError):
                pass
            if time.monotonic() > deadline: raise RuntimeError('Owned ES startup deadline')
            time.sleep(.2)
        env = os.environ.copy(); env['WEKNORA_ES_PARITY_URL'] = address
        fixture = output / 'go-es8-index-move.json'
        with (output / 'go.log').open('wb') as log:
            command(['go', 'run', 'backend-java/src/test/fixtures/elasticsearch_index_move_physical_fixture.go', str(fixture)],
                    env=env, stdout=log, stderr=subprocess.STDOUT)
        rows = json.loads(fixture.read_text()); assert len(rows) == 22
        # Existing retrieval/deletion parity runs against the same owned engine as a regression.
        retrieval = output / 'go-es8-retrieval.json'
        with (output / 'go-retrieval.log').open('wb') as log:
            command(['go', 'run', 'backend-java/src/test/fixtures/vector_store_es_fixture.go', str(retrieval)],
                    env=env, stdout=log, stderr=subprocess.STDOUT)
        with (output / 'java.log').open('wb') as log:
            command(['mvn', '-q', '-f', 'backend-java/pom.xml', '-Dweknora.build.directory=' + str(output / 'build'),
                     '-Dweknora.es.url=' + address, '-Dweknora.es.move.fixture=' + str(fixture),
                     '-Dweknora.es.fixture=' + str(retrieval), '-Dtest=Elasticsearch8MoveGoParityIT,Elasticsearch8GoParityIT',
                     'clean', 'test'], stdout=log, stderr=subprocess.STDOUT)
        print(json.dumps({'cases': len(rows), 'mapping_types': 2, 'snapshots': 66, 'retrieval_queries': 396,
                          'existing_retrieval_regression_cases': 2, 'artifacts': str(output)}))
    finally:
        if container:
            with (output / 'engine.log').open('wb') as log:
                subprocess.run([args.docker, 'logs', container], stdout=log, stderr=subprocess.STDOUT, check=False)
            stopped = subprocess.run([args.docker, 'stop', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            (output / 'container-stop.json').write_text(json.dumps({'container': container, 'exit_code': stopped.returncode}) + '\n')
        print('ES8 index move artifacts: ' + str(output))


if __name__ == '__main__':
    main()
