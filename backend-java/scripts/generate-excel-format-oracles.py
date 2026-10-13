#!/usr/bin/env python3
"""Recompute Java Excel fixtures with pinned actual Go nfp/Excelize; no production edits."""
import argparse, gzip, json, os, shutil, signal, subprocess, tempfile
from pathlib import Path


def json_gzip(path, value):
    with path.open("wb") as output, gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as stream:
        stream.write((json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--fixture-dir", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--table-output", type=Path)
    parser.add_argument("--rows-input", type=Path, help="OOXML input corpus; default reads raw input fields from existing Rows golden")
    parser.add_argument("--nontermination-input", type=Path)
    args = parser.parse_args()
    repo = args.repo.resolve()
    fixtures = args.fixture_dir or repo / "backend-java/src/test/fixtures"
    output = args.output_dir or repo / "backend-java/src/test/resources"
    output.mkdir(parents=True, exist_ok=True)
    table_output = args.table_output or repo / "backend-java/src/main/resources/META-INF/excelize-number-format-tables.json"
    excelize = Path(subprocess.check_output(["go", "list", "-m", "-f", "{{.Dir}}", "github.com/xuri/excelize/v2"], cwd=repo, text=True).strip())
    nfp = Path(subprocess.check_output(["go", "list", "-m", "-f", "{{.Dir}}", "github.com/xuri/nfp"], cwd=repo, text=True).strip())
    seed = args.rows_input or output / "go-excel-rows.json.gz"
    with (gzip.open(seed, "rt") if seed.suffix == ".gz" else seed.open()) as stream:
        docs = json.load(stream)
    # These committed OOXML byte inputs include raw custom format/style/category edge cases.
    docs = [{key: doc[key] for key in ("name", "file_type", "data")} for doc in docs]
    nontermination = json.loads((args.nontermination_input or output / "go-excel-nontermination.json").read_text())
    with tempfile.TemporaryDirectory(prefix="weknora-excel-oracle-") as directory:
        temp = Path(directory)
        module = temp / "excelize"
        shutil.copytree(excelize, module)
        for p in [module, *module.rglob("*")]:
            p.chmod(0o755 if p.is_dir() else 0o644)
        # Private helpers expose no runtime Go API; upstream test-only dependencies are not required.
        for p in module.glob("*_test.go"):
            p.unlink()
        shutil.copyfile(fixtures / "excel_format_oracle.go.txt", module / "java_excel_format_oracle_test.go")
        modfile = temp / "go.mod"
        shutil.copyfile(repo / "go.mod", modfile)
        shutil.copyfile(repo / "go.sum", temp / "go.sum")
        with modfile.open("a") as stream:
            stream.write(f"\nreplace github.com/xuri/excelize/v2 => {module}\n")
        env = dict(os.environ, WEKNORA_EXCELIZE_TEST_SOURCE=str(excelize / "numfmt_test.go"),
                   WEKNORA_EXCEL_TABLES_OUTPUT=str(temp / "tables.json"), WEKNORA_EXCEL_FORMAT_OUTPUT=str(temp / "format.json"))
        subprocess.run(["go", "test", f"-modfile={modfile}", "-mod=mod", "github.com/xuri/excelize/v2", "-run", "^TestJavaExcelizeFormatOracle$", "-count=1"], cwd=repo, env=env, check=True)
        formats = json.loads((temp / "format.json").read_text())
        json_gzip(output / "go-excel-format.json.gz", formats)
        table_output.parent.mkdir(parents=True, exist_ok=True)
        table_output.write_text(json.dumps(json.loads((temp / "tables.json").read_text()), ensure_ascii=False, separators=(",", ":")) + "\n")
        virtual_parser = repo / "backend-java/src/test/fixtures/java_excel_format_parser_stage.go"
        virtual_rows = repo / "backend-java/src/test/fixtures/java_excel_format_rows_stage.go"
        if virtual_parser.exists() or virtual_rows.exists():
            raise RuntimeError("Refusing to shadow an existing fixture")
        overlay = temp / "overlay.json"
        overlay.write_text(json.dumps({"Replace": {str(virtual_parser): str((fixtures / "excel_format_parser_fixture.go").resolve()), str(virtual_rows): str((fixtures / "excel_rows_fixture.go").resolve())}}))
        env["WEKNORA_NFP_TEST_SOURCE"] = str(nfp / "nfp_test.go")
        parsed = subprocess.check_output(["go", "run", f"-modfile={modfile}", "-mod=mod", "-overlay", str(overlay), str(virtual_parser)], cwd=repo, env=env)
        parsers = json.loads(parsed)
        json_gzip(output / "go-format-parser.json.gz", parsers)
        input_file = temp / "rows-input.json"
        input_file.write_text(json.dumps(docs, separators=(",", ":")))
        env["WEKNORA_EXCEL_ROWS_INPUT"] = str(input_file)
        executable = temp / "rows-oracle"
        subprocess.run(["go", "build", f"-modfile={modfile}", "-mod=mod", "-overlay", str(overlay), "-o", str(executable), str(virtual_rows)], cwd=repo, check=True)
        rows = json.loads(subprocess.check_output([str(executable)], cwd=repo, env=env, timeout=90))
        json_gzip(output / "go-excel-rows.json.gz", rows)
        input_file.write_text(json.dumps([{key: nontermination[key] for key in ("name", "file_type", "data")}]))
        process = subprocess.Popen([str(executable)], cwd=repo, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        try:
            stdout, stderr = process.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            nontermination["actual_go_timeout_seconds"] = 3
        else:
            raise AssertionError(f"Go non-terminating fraction unexpectedly returned: {stdout!r} {stderr!r}")
        nontermination["go_version"] = subprocess.check_output(["go", "env", "GOVERSION"], cwd=repo, text=True).strip()
        nontermination["go_arch"] = subprocess.check_output(["go", "env", "GOARCH"], cwd=repo, text=True).strip()
        (output / "go-excel-nontermination.json").write_text(json.dumps(nontermination, separators=(",", ":")) + "\n")
        print(f"Actual Go: {len(parsers)} parser cases; {len(formats)} formats; {len(rows)} workbooks/{sum(len(doc.get('units') or []) for doc in rows)} units; bounded non-terminating fraction")

if __name__ == "__main__":
    main()
