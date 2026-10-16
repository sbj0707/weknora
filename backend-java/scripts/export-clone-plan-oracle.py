#!/usr/bin/env python3
"""Execute actual Go clone compatibility and document/FAQ preflight using a test overlay."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root, output = args.root.resolve(), args.output.resolve()
    with tempfile.TemporaryDirectory(prefix='weknora-clone-plan-overlay-') as directory:
        work = Path(directory)
        fixture = work / 'java_clone_plan_fixture_test.go'
        original = root / 'backend-java/src/test/fixtures/knowledge_clone_plan_fixture_test.go'
        fixture.write_text(original.read_text().replace('//go:build ignore\n', '', 1))
        overlay = work / 'overlay.json'
        overlay.write_text(json.dumps({'Replace': {
            str(root / 'internal/application/service/java_clone_plan_fixture_test.go'): str(fixture)}}))
        env = os.environ.copy()
        env['WEKNORA_CLONE_PLAN_ORACLE'] = str(output)
        subprocess.run(['go', 'test', '-overlay', str(overlay), '-tags', 'sqlite_fts5',
                        './internal/application/service', '-run', '^TestJavaKnowledgeClonePlanExport$',
                        '-count=1'], cwd=root, env=env, check=True)
        print(json.dumps({k: len(v) for k, v in json.loads(output.read_text()).items()}))


if __name__ == '__main__':
    main()
