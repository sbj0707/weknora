#!/usr/bin/env python3
"""Run actual Go Office extraction and its private production attachment via an ephemeral overlay."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--fixture-dir", type=Path, help="Optional fixture source override for staging")
    args = parser.parse_args()
    repo = args.repo.resolve()
    fixtures = args.fixture_dir or repo / "backend-java/src/test/fixtures"
    output = args.output_dir or repo / "backend-java/src/test/resources"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="weknora-office-oracle-") as directory:
        temp = Path(directory)
        source = temp / "go-office-source.json"
        attachment = temp / "go-office-attachment.json"
        virtual_source = repo / "backend-java/src/test/fixtures/java_office_source_oracle_stage.go"
        if virtual_source.exists():
            raise RuntimeError(f"Refusing to shadow an existing fixture: {virtual_source}")
        source_overlay = temp / "source-overlay.json"
        source_overlay.write_text(json.dumps({"Replace": {str(virtual_source): str((fixtures / "office_source_fixture.go").resolve())}}))
        with source.open("wb") as stream:
            subprocess.run(["go", "run", "-overlay", str(source_overlay), str(virtual_source)], cwd=repo, stdout=stream, check=True)
        test_source = temp / "office_attachment_fixture_test.go"
        test_source.write_text((fixtures / "office_attachment_oracle.go.txt").read_text())
        virtual = repo / "internal/application/service/java_office_attachment_oracle_test.go"
        if virtual.exists():
            raise RuntimeError(f"Refusing to shadow an existing production test: {virtual}")
        overlay = temp / "overlay.json"
        overlay.write_text(json.dumps({"Replace": {str(virtual): str(test_source)}}))
        env = dict(os.environ, WEKNORA_OFFICE_ORACLE_INPUT=str(source), WEKNORA_OFFICE_ORACLE_OUTPUT=str(attachment))
        subprocess.run(["go", "test", "-overlay", str(overlay), "./internal/application/service", "-run", "^TestJavaOfficeAttachmentOracle$", "-count=1"], cwd=repo, env=env, check=True)
        source_data = json.loads(source.read_text())
        attachment_data = json.loads(attachment.read_text())
        for name, data in [(source.name, source_data), (attachment.name, attachment_data)]:
            (output / name).write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")
        print(f"Actual Go: {sum(not f.get('java_pending') and 'unit_count' not in f for f in source_data)} extraction/alignment cases; {sum('unit_count' in f for f in source_data)} bounded full-output digests; {len(attachment_data)} production attachment cases; {sum(f.get('java_pending', False) for f in source_data)} explicit migration gaps")


if __name__ == "__main__":
    main()
