#!/usr/bin/env python3
"""Replay original Go OIDC state/config/handler/provider transport into test fixtures."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--groups', nargs='+', choices=('state', 'config', 'http', 'transport'),
                        default=('state', 'config', 'http', 'transport'))
    args = parser.parse_args()
    repo = args.repo.resolve()
    source = repo / 'backend-java/src/test/java/com/tencent/weknora/security'
    destination = args.output_dir or source
    destination.mkdir(parents=True, exist_ok=True)
    counts = {'state': 94, 'config': 52, 'http': 149, 'transport': 18}
    for group in args.groups:
        result = subprocess.run(['go', 'run', str(source / ('oidc_' + group + '_golden.go'))],
                                cwd=repo, capture_output=True, text=True)
        (destination / ('oidc-' + group + '-replay.log')).write_text(result.stderr)
        if result.returncode:
            raise SystemExit('Go OIDC ' + group + ' replay failed; see replay log')
        decoded = None
        decoder = json.JSONDecoder()
        for offset, char in enumerate(result.stdout):
            if char not in '[{':
                continue
            try:
                value, length = decoder.raw_decode(result.stdout[offset:])
            except ValueError:
                continue
            if result.stdout[offset + length:].strip():
                continue
            if group == 'state':
                if not isinstance(value, dict) or not all(isinstance(value.get(k), list) for k in ('sign', 'verify')):
                    continue
                count = len(value['sign']) + len(value['verify'])
            else:
                if not isinstance(value, list):
                    continue
                count = len(value)
            if count == counts[group]:
                decoded = value
                break
        if decoded is None:
            raise SystemExit('Missing complete Go OIDC ' + group + ' fixture JSON')
        (destination / ('oidc-' + group + '-golden.json')).write_text(
            json.dumps(decoded, ensure_ascii=False, indent=2) + '\n')
        print(group + ': ' + str(counts[group]) + ' actual Go cases')
    print('State/HTTP fixtures carry their own current Go clock; validate regenerated fixtures with Java tests.')


if __name__ == '__main__':
    main()
