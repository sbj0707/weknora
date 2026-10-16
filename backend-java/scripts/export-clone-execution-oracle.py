#!/usr/bin/env python3
"""Export actual Go ordinary KB clone and retry state using isolated SQLite databases."""
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
    # The shared chunk fixture can target PostgreSQL, but execution uses SQLite-only triggers
    # and a different tenant fixture. Never inherit that destructive external database setup.
    if os.environ.get('WEKNORA_CLONE_CHUNKS_PG_DSN'):
        parser.error('This exporter supports SQLite only; unset WEKNORA_CLONE_CHUNKS_PG_DSN')
    root, output = Path(__file__).resolve().parents[2], args.output.resolve()
    with tempfile.TemporaryDirectory(prefix='weknora-clone-execution-overlay-') as directory:
        replacements = {}
        for name in ('chunks', 'execution'):
            fixture = Path(directory) / f'java_clone_{name}_fixture_test.go'
            source = root / f'backend-java/src/test/fixtures/knowledge_clone_{name}_fixture_test.go'
            fixture.write_text(source.read_text().replace('//go:build ignore\n', '', 1))
            replacements[str(root / f'internal/application/service/java_clone_{name}_fixture_test.go')] = str(fixture)
        overlay = Path(directory) / 'overlay.json'
        overlay.write_text(json.dumps({'Replace': replacements}))
        env = os.environ.copy()
        env['WEKNORA_CLONE_EXECUTION_ORACLE'] = str(output)
        subprocess.run(['go', 'test', '-overlay', str(overlay), '-tags', 'sqlite_fts5',
                        './internal/application/service', '-run', '^TestJavaKnowledgeCloneExecutionExport$',
                        '-count=1'], cwd=root, env=env, check=True)
        print(json.dumps({'cases': len(json.loads(output.read_text()))}))


if __name__ == '__main__':
    main()
