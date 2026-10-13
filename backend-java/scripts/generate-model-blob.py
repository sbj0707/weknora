#!/usr/bin/env python3
"""Replay production Go SQLite BLOB/native model HTTP cases, ignoring logger prefixes."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
parser.add_argument('--fixture', type=Path)
parser.add_argument('--output', type=Path)
parser.add_argument('--compare-to', type=Path)
args = parser.parse_args()
repo = args.repo.resolve()
fixture = args.fixture or repo / 'backend-java/src/test/java/com/tencent/weknora/model/client/model_blob_fixture.go'
output = args.output or repo / 'backend-java/src/test/java/com/tencent/weknora/model/client/go-model-blob.json'
run = subprocess.run(['go', 'run', str(fixture)], cwd=repo, text=True, capture_output=True)
if run.returncode:
    raise SystemExit(run.stderr + run.stdout)
decoder = json.JSONDecoder()
for offset, char in enumerate(run.stdout):
    if char != '[':
        continue
    try:
        cases, end = decoder.raw_decode(run.stdout[offset:])
    except ValueError:
        continue
    if isinstance(cases, list) and len(cases) == 6 and all(isinstance(c, dict) and c.get('sqlite_type') == 'blob' and len(c.get('requests', [])) == 1 for c in cases) and not run.stdout[offset + end:].strip():
        break
else:
    raise SystemExit('Missing final six-case production BLOB fixture JSON')
if args.compare_to and json.loads(args.compare_to.read_text()) != cases:
    raise SystemExit('Generated Go BLOB fixture differs from comparison file')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
print(f'6 production Go BLOB cases / 6 actual model HTTP requests written to {output}')
