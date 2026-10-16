#!/usr/bin/env python3
"""Run actual Go cloneKnowledge with real SQL and controlled external boundaries."""
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
    with tempfile.TemporaryDirectory(prefix='weknora-clone-document-overlay-') as directory:
        work = Path(directory)
        replacements = {}
        for name in ('chunks', 'document'):
            fixture = work / f'java_clone_{name}_fixture_test.go'
            original = root / f'backend-java/src/test/fixtures/knowledge_clone_{name}_fixture_test.go'
            fixture.write_text(original.read_text().replace('//go:build ignore\n', '', 1))
            replacements[str(root / f'internal/application/service/java_clone_{name}_fixture_test.go')] = str(fixture)
        overlay = work / 'overlay.json'
        overlay.write_text(json.dumps({'Replace': replacements}))
        env = os.environ.copy()
        env['WEKNORA_CLONE_DOCUMENT_ORACLE'] = str(output)
        subprocess.run(['go', 'test', '-overlay', str(overlay), '-tags', 'sqlite_fts5',
                        './internal/application/service', '-run', '^TestJavaKnowledgeCloneDocumentExport$',
                        '-count=1'], cwd=root, env=env, check=True)
        print(json.dumps({'cases': len(json.loads(output.read_text()))}))


if __name__ == '__main__':
    main()
