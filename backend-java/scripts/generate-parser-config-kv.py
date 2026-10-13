#!/usr/bin/env python3
"""Reproduce parser KV fixtures through the actual Go handler/service/SQLite repository."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--compare-to', type=Path)
    parser.add_argument('--log', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    fixture = repo / 'backend-java/src/test/fixtures/parser_config_kv_fixture.go'
    with tempfile.TemporaryDirectory(prefix='weknora-parser-kv-oracle-') as temporary:
        artifact = Path(temporary) / 'cases.json'
        result = subprocess.run(['go', 'run', str(fixture), str(artifact)], cwd=repo,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if args.log:
            args.log.parent.mkdir(parents=True, exist_ok=True)
            args.log.write_bytes(result.stdout)
        if result.returncode:
            raise RuntimeError(f'Go fixture exited {result.returncode}; ' + result.stdout.decode(errors='replace'))
        cases = json.loads(artifact.read_bytes())
        if not isinstance(cases, list) or len(cases) != 1092:
            raise ValueError('Expected 1,092 production Go parser KV scenes')
        if args.compare_to and cases != json.loads(args.compare_to.read_bytes()):
            raise ValueError('Regenerated parser KV fixture differs from checked reference')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(artifact.read_bytes())
    print(f'1,092 production Go parser KV scenes reproduced: {args.output}')


if __name__ == '__main__':
    main()
