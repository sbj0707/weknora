#!/usr/bin/env bash
set -euo pipefail
# Compile the locked C++ dependency only; the Java process does not execute Go code.
weknora_jieba_output="${1:-/tmp/weknora-jieba-native}"
case "$weknora_jieba_output" in /*) ;; *) echo "Output directory must be absolute" >&2; exit 1 ;; esac
weknora_jieba_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$weknora_jieba_root"
weknora_jieba_version="$(go list -m -f '{{.Version}}' github.com/yanyiwu/gojieba)"
[[ "$weknora_jieba_version" == v1.4.7 ]] || { echo "Production Go Jieba dependency must be v1.4.7" >&2; exit 1; }
weknora_jieba_module="$(go list -m -f '{{.Dir}}' github.com/yanyiwu/gojieba)"
weknora_jieba_cpp="$weknora_jieba_module/deps/cppjieba"
[[ -f "$weknora_jieba_cpp/include/cppjieba/Jieba.hpp" ]] || { echo "Download the existing locked Go modules first" >&2; exit 1; }
weknora_jieba_jdk="${2:-}"
if [[ -z "$weknora_jieba_jdk" ]]; then
  case "$(uname -s)" in
    Darwin) weknora_jieba_jdk="$(/usr/libexec/java_home -v 21)" ;;
    Linux) weknora_jieba_jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")" ;;
    *) echo "This build script supports Darwin and Linux" >&2; exit 1 ;;
  esac
fi
mkdir -p "$weknora_jieba_output/dict"
case "$(uname -s)" in
  Darwin) weknora_jieba_platform=darwin; weknora_jieba_flags=(-dynamiclib); weknora_jieba_library="$weknora_jieba_output/libweknora_jieba.dylib" ;;
  Linux) weknora_jieba_platform=linux; weknora_jieba_flags=(-shared); weknora_jieba_library="$weknora_jieba_output/libweknora_jieba.so" ;;
  *) echo "This build script supports Darwin and Linux" >&2; exit 1 ;;
esac
"${CXX:-c++}" -std=c++17 -O2 -fPIC "${weknora_jieba_flags[@]}" \
  -I"$weknora_jieba_jdk/include" -I"$weknora_jieba_jdk/include/$weknora_jieba_platform" \
  -I"$weknora_jieba_cpp/include" -I"$weknora_jieba_cpp/deps/limonp/include" \
  "$weknora_jieba_root/backend-java/src/main/native/weknora-jieba.cpp" -o "$weknora_jieba_library"
for weknora_jieba_dictionary in jieba.dict.utf8 hmm_model.utf8 user.dict.utf8 idf.utf8 stop_words.utf8; do
  install -m 644 "$weknora_jieba_cpp/dict/$weknora_jieba_dictionary" "$weknora_jieba_output/dict/$weknora_jieba_dictionary"
done
install -m 644 "$weknora_jieba_cpp/LICENSE" "$weknora_jieba_output/cppjieba-LICENSE"
install -m 644 "$weknora_jieba_cpp/deps/limonp/LICENSE" "$weknora_jieba_output/limonp-LICENSE"
printf '%s\n%s\n' "$weknora_jieba_library" "$weknora_jieba_output/dict"
