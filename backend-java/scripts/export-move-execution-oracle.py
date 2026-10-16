#!/usr/bin/env python3
"""Execute the production Go per-document move and its real SQL repositories in an overlay."""
import argparse, json, os, subprocess, tempfile
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    with tempfile.TemporaryDirectory(prefix='weknora-move-execution-overlay-') as directory:
        work = Path(directory)
        replacements = {}
        for name in ['knowledge_move_plan_fixture_test.go', 'knowledge_move_execution_fixture_test.go']:
            fixture = work / name
            fixture.write_text((root/'backend-java/src/test/fixtures'/name).read_text().replace('//go:build ignore\n', '', 1))
            replacements[str(root/'internal/application/service'/('java_'+name))] = str(fixture)
        overlay = work/'overlay.json'
        overlay.write_text(json.dumps({'Replace': replacements}))
        env = os.environ.copy()
        env['WEKNORA_MOVE_EXECUTION_ORACLE'] = str(args.output.resolve())
        subprocess.run(['go', 'test', '-overlay', str(overlay), '-tags', 'sqlite_fts5', './internal/application/service', '-run', '^TestJavaKnowledgeMoveExecutionExport$', '-count=1'], cwd=root, env=env, check=True)
        rows = json.loads(args.output.read_text())
        print(json.dumps({'cases': len(rows)}))

if __name__ == '__main__':
    main()
