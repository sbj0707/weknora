#!/usr/bin/env python3
"""Reproduce production Go parser discovery/probes/redirects/settings fixtures without editing Go sources."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile

FIXTURES = (
    ("parser_registry_fixture.go", "go-parser-registry.json", 108),
    ("parser_probe_fixture.go", "go-parser-probes.json", 408),
    ("parser_redirect_fixture.go", "go-parser-redirects.json", 18),
    ("system_parser_fixture.go", "go-system-parser.json", 121),
    ("parser_settings_schema.go", "parser-settings-schema.json", None),
)


def final_json(stdout):
    """The Go logger can precede the final JSON; require that JSON consumes the remaining output."""
    text = stdout.decode("utf-8")
    decoder = json.JSONDecoder()
    candidates = [match.start() for match in re.finditer(r"(?m)^[\[{]", text)]
    for start in reversed(candidates):
        try:
            value, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            continue
        if not text[end:].strip():
            return value
    raise ValueError("Go fixture output has no complete final JSON value")


def validate(name, value, expected_count):
    if expected_count is not None:
        if not isinstance(value, list) or len(value) != expected_count:
            raise ValueError(f"{name}: expected {expected_count} cases, got "
                             f"{len(value) if isinstance(value, list) else type(value).__name__}")
    elif (not isinstance(value, dict) or set(value) != {"config", "credentials"}
          or any(value[key].get("kind") != "struct" or not value[key].get("fields")
                 for key in ("config", "credentials"))):
        raise ValueError(f"{name}: expected populated config/credentials struct schemas")


def loopback_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output-dir", type=Path, help="Default: backend-java/src/test/resources")
    parser.add_argument("--fixture-dir", type=Path, help="Optional fixture source override for staging")
    parser.add_argument("--schema-output-dir", type=Path, help="Default: main/resources; --output-dir also redirects schema")
    parser.add_argument("--schema-compare-to", type=Path, help="Schema comparison directory; default: main/resources")
    parser.add_argument("--compare-to", type=Path, help="Fail if any regenerated JSON differs semantically")
    parser.add_argument("--log-dir", type=Path, help="Keep complete Go stdout/stderr for review")
    parser.add_argument("--dry-run", action="store_true", help="Review inputs, destinations and overlay only")
    args = parser.parse_args()
    repo = args.repo.resolve()
    if not (repo / "go.mod").is_file():
        parser.error(f"not a Go repository: {repo}")
    fixtures = (args.fixture_dir or repo / "backend-java/src/test/fixtures").resolve()
    output = (args.output_dir or repo / "backend-java/src/test/resources").resolve()
    schema_output = (args.schema_output_dir or args.output_dir
                     or repo / "backend-java/src/main/resources").resolve()
    schema_compare = (args.schema_compare_to or repo / "backend-java/src/main/resources").resolve()
    cloud = repo / "internal/infrastructure/docparser/mineru_cloud_converter.go"
    pattern = r'(?m)^(\s*defaultBaseURL\s*=\s*)"https://mineru\.net/api/v4"(\s*)$'
    original = cloud.read_text()
    if len(re.findall(pattern, original)) != 1:
        raise ValueError("Cloud endpoint changed: expected exactly one production defaultBaseURL constant")
    for source, name, count in FIXTURES:
        if not (fixtures / source).is_file():
            raise FileNotFoundError(fixtures / source)
        if args.dry_run:
            destination_dir = output if count is not None else schema_output
            print(f"go run {fixtures / source} -> {destination_dir / name} "
                  f"({count if count is not None else 'config/credentials schema'})")
    if args.dry_run:
        print("Probe only: temporary Go overlay changes defaultBaseURL to 127.0.0.1:<random-port>/api/v4; "
              "WEKNORA_FIXTURE_PORT uses the same port. Production Go files remain untouched.")
        return
    values = {}
    if args.log_dir:
        args.log_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="weknora-parser-oracles-") as directory:
        temp = Path(directory)
        for source, name, count in FIXTURES:
            command = ["go", "run"]
            env = dict(os.environ)
            if source == "parser_probe_fixture.go":
                port = loopback_port()
                replacement = temp / "mineru_cloud_converter.go"
                rewritten, changes = re.subn(
                    pattern, lambda match: match.group(1)
                    + json.dumps(f"http://127.0.0.1:{port}/api/v4") + match.group(2), original)
                if changes != 1:
                    raise ValueError("Cloud endpoint overlay did not replace exactly one constant")
                replacement.write_text(rewritten)
                overlay = temp / "probe-overlay.json"
                overlay.write_text(json.dumps({"Replace": {str(cloud): str(replacement)}}))
                command += ["-overlay", str(overlay)]
                env["WEKNORA_FIXTURE_PORT"] = str(port)
            command.append(str(fixtures / source))
            result = subprocess.run(command, cwd=repo, env=env, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, check=False)
            if args.log_dir:
                (args.log_dir / f"{source}.log").write_bytes(result.stdout + result.stderr)
            if result.returncode:
                raise RuntimeError(f"{source}: Go exited {result.returncode}\n"
                                   + result.stderr.decode("utf-8", errors="replace"))
            value = final_json(result.stdout)
            validate(name, value, count)
            if args.compare_to:
                comparison = (args.compare_to if count is not None else schema_compare) / name
                expected = json.loads(comparison.read_text())
                if value != expected:
                    raise ValueError(f"{name}: regenerated JSON differs from {comparison}")
            values[name] = value
            print(f"Actual Go: {name}: {count if count is not None else 'config/credentials schema'} verified",
                  flush=True)
    # Validate every fixture before changing any destination.
    output.mkdir(parents=True, exist_ok=True)
    for name, value in values.items():
        destination_dir = schema_output if name == "parser-settings-schema.json" else output
        destination_dir.mkdir(parents=True, exist_ok=True)
        destination = destination_dir / name
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=destination_dir,
                                         prefix=f".{name}.", delete=False) as stream:
            staging = Path(stream.name)
            json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n")
        try:
            os.replace(staging, destination)
        finally:
            staging.unlink(missing_ok=True)
    print(f"Wrote 655 production Go cases to {output}; typed settings schema to {schema_output}")


if __name__ == "__main__":
    main()
