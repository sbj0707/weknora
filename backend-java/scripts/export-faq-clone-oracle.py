#!/usr/bin/env python3
"""Export actual Go FAQ clone stages and retries from independent SQLite databases."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if os.environ.get('WEKNORA_CLONE_CHUNKS_PG_DSN'):
        parser.error('SQLite only; unset WEKNORA_CLONE_CHUNKS_PG_DSN')
    root = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix='weknora-faq-clone-') as directory:
        replacements = {}
        for name in ('knowledge_clone_chunks', 'knowledge_faq_clone'):
            fixture = Path(directory) / (name + '_fixture_test.go')
            fixture.write_text((root / ('backend-java/src/test/fixtures/' + name + '_fixture_test.go'))
                               .read_text().replace('//go:build ignore\n', '', 1))
            replacements[str(root / ('internal/application/service/java_' + name + '_fixture_test.go'))] = str(fixture)
        overlay = Path(directory) / 'overlay.json'
        overlay.write_text(json.dumps({'Replace': replacements}))
        env = os.environ.copy()
        env['WEKNORA_FAQ_CLONE_ORACLE'] = str(args.output.resolve())
        env['WEKNORA_FAQ_CLONE_ROOT'] = str(root)
        subprocess.run(['go', 'test', '-overlay', str(overlay), '-tags', 'sqlite_fts5',
                        './internal/application/service', '-run', '^TestJavaKnowledgeFAQCloneExport$', '-count=1'],
                       cwd=root, env=env, check=True)
        print(json.dumps({'cases': len(json.loads(args.output.read_text()))}))


if __name__ == '__main__':
    main()
