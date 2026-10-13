#!/usr/bin/env python3
"""Run original Go DuckDB loader and production summary service into differential fixtures."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def run(repo, output, overlay, source, args, name, expected):
    result = subprocess.run(
        ['go', 'run', '-tags', 'sqlite_fts5', '-overlay', str(overlay), str(source), *args],
        cwd=repo, capture_output=True, text=True)
    (output / (name + '-replay.log')).write_text(result.stderr)
    if result.returncode:
        raise SystemExit(name + ' Go replay failed; see replay log')
    decoder = json.JSONDecoder()
    for offset, char in enumerate(result.stdout):
        if char != '[':
            continue
        try:
            rows, length = decoder.raw_decode(result.stdout[offset:])
        except ValueError:
            continue
        if result.stdout[offset + length:].strip() or not isinstance(rows, list):
            continue
        if len(rows) != expected:
            raise SystemExit(name + ': unexpected fixture count')
        path = output / (name + '.json')
        path.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
        print(str(path) + ': ' + str(len(rows)) + ' actual Go cases')
        return path
    raise SystemExit(name + ': missing complete JSON result')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = (args.output_dir or repo / 'backend-java/src/test/resources').resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = repo / 'backend-java/src/test/fixtures'
    original = repo / 'internal/application/service/extract.go'
    with tempfile.TemporaryDirectory(prefix='weknora-datatable-oracle-') as folder:
        folder = Path(folder)
        adapted = folder / 'extract.go'
        # Expose the existing private sample formatter; preserve all original application logic.
        adapted.write_text(original.read_text() + '\nfunc MigrationTableSample(sample *types.ToolResult) string { '
            'return (&DataTableSummaryService{}).buildSampleDataDescription(context.Background(), sample, 10) }\n')
        overlay = folder / 'overlay.json'
        overlay.write_text(json.dumps({'Replace': {str(original): str(adapted)}}))
        loader = run(repo, output, overlay, sources / 'datatable_loader_fixture.go', [],
                     'go-datatable-loader', 10)
        run(repo, output, overlay, sources / 'datatable_summary_fixture.go', [str(loader)],
            'go-datatable-summary', 37)


if __name__ == '__main__':
    main()
