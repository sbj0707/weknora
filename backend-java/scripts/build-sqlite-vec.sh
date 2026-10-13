#!/usr/bin/env bash
set -euo pipefail

# Compile the exact native C extension used by the production Go dependency.
# No Go application/business code runs in the Java process.
weknora_vec_output="${1:-/tmp/weknora-sqlite-vec-native}"
case "$weknora_vec_output" in /*) ;; *) echo "Output directory must be absolute" >&2; exit 1 ;; esac
weknora_vec_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$weknora_vec_root"
weknora_vec_version="$(go list -m -f '{{.Version}}' github.com/asg017/sqlite-vec-go-bindings)"
if [[ "$weknora_vec_version" != 'v0.1.6' ]]; then
  echo "Production Go sqlite-vec dependency must be v0.1.6" >&2
  exit 1
fi
weknora_vec_module="$(go list -m -f '{{.Dir}}' github.com/asg017/sqlite-vec-go-bindings)"
weknora_vec_sqlite="$(go list -m -f '{{.Dir}}' github.com/mattn/go-sqlite3)"
if [[ ! -f "$weknora_vec_module/cgo/sqlite-vec.c" || ! -f "$weknora_vec_sqlite/sqlite3ext.h" ]]; then
  echo "Download the existing locked Go modules before compiling the extension" >&2
  exit 1
fi
mkdir -p "$weknora_vec_output"
case "$(uname -s)" in
  Darwin)
    "${CC:-cc}" -dynamiclib -O2 -I"$weknora_vec_sqlite" -o "$weknora_vec_output/vec0.dylib" "$weknora_vec_module/cgo/sqlite-vec.c"
    weknora_vec_library="$weknora_vec_output/vec0.dylib"
    ;;
  Linux)
    "${CC:-cc}" -shared -fPIC -O2 -I"$weknora_vec_sqlite" -o "$weknora_vec_output/vec0.so" "$weknora_vec_module/cgo/sqlite-vec.c" -lm
    weknora_vec_library="$weknora_vec_output/vec0.so"
    ;;
  *) echo "This build script supports Darwin and Linux" >&2; exit 1 ;;
esac
cp "$weknora_vec_root/backend-java/src/main/resources/licenses/sqlite-vec-LICENSE-MIT" "$weknora_vec_output/sqlite-vec-LICENSE-MIT"
printf '%s\n' "$weknora_vec_library"
