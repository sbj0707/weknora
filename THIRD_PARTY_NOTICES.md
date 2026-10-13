# Third-party notices and corresponding source

This file supplements the third-party notices in `LICENSE`. The MIT license
for WeKnora's own code does not replace the licenses of third-party components.
Keep this file, `LICENSE`, and the `licenses/` directory with redistributed
backend and desktop packages. `scripts/copy-licenses.sh` adds checksum-verified
source archives at packaging time; they are not stored in this Git repository.
Container distributions include the complete bundle in `/app`;
macOS applications include them in `Contents/Resources`.
Windows installers place them next to the installed executable.

## Go MySQL Driver

- Component: `github.com/go-sql-driver/mysql`, version `v1.10.0`.
- Used in backend and desktop binaries, including the Doris MySQL protocol driver.
- License: Mozilla Public License 2.0, reproduced in
  [`licenses/go-sql-driver-mysql-MPL-2.0.txt`](licenses/go-sql-driver-mysql-MPL-2.0.txt).
- Copyright: The Go-MySQL-Driver Authors; the original per-file notices and
  `AUTHORS` are preserved in the accompanying source archive.
- Modifications by WeKnora: none. Dialer configuration is in separate WeKnora files.
- Corresponding source, available under MPL-2.0, is included in binary releases
  as `licenses/sources/mysql-v1.10.0.zip`. Source repository users can obtain it from the
  [Go module proxy](https://proxy.golang.org/github.com/go-sql-driver/mysql/@v/v1.10.0.zip)
  and [upstream repository](https://github.com/go-sql-driver/mysql/tree/v1.10.0).

## go-m1cpu

- Component: `github.com/shoenig/go-m1cpu`, version `v0.1.6`.
- Used by the macOS backend/desktop dependency chain through gopsutil. It is
  absent from Linux backend build dependencies. Its source is included in all
  notice bundles for consistent packaging; this does not imply Linux linkage.
- License: Mozilla Public License 2.0, reproduced in
  [`licenses/go-m1cpu-MPL-2.0.txt`](licenses/go-m1cpu-MPL-2.0.txt).
- Attribution: the go-m1cpu project and its contributors. The complete original
  source and notices are preserved in the accompanying archive.
- Modifications by WeKnora: none.
- Corresponding source, available under MPL-2.0, is included in binary releases
  as `licenses/sources/go-m1cpu-v0.1.6.zip`. Source repository users can obtain it from the
  [Go module proxy](https://proxy.golang.org/github.com/shoenig/go-m1cpu/@v/v0.1.6.zip)
  and [upstream repository](https://github.com/shoenig/go-m1cpu/tree/v0.1.6).

## OpenCC dictionary data

- Files: `internal/textconv/data/TSPhrases.txt` and `TSCharacters.txt`, copied
  unchanged from `github.com/longbridgeapp/opencc` version `v0.3.13`.
- Attribution: the OpenCC and longbridge/opencc contributors.
- License: Apache-2.0, reproduced in
  [`licenses/OpenCC-Apache-2.0.txt`](licenses/OpenCC-Apache-2.0.txt).
- Source: [versioned dictionary directory](https://github.com/longbridgeapp/opencc/tree/v0.3.13/dictionary)
  and the two text files distributed with WeKnora's source.
- Only dictionary data is retained. WeKnora uses its own standard-library lookup
  implementation; the upstream Go converter, `liuzl/da`, and GPL-licensed
  `cedar-go` code are not included.

## Build-only component

The Windows installer template in `cmd/desktop/build/windows/installer/project.nsi`
is based on Wails v2.12.0's MIT-licensed default template, with local changes to
install this notice/source bundle. Its license and copyright are reproduced in
[`licenses/Wails-MIT.txt`](licenses/Wails-MIT.txt).

`cbindgen` 0.29.4 (MPL-2.0) generates the AnyDoc C ABI header during the Rust
build. Its code/tool executable is not copied into the runtime image or release
packages by the current build recipes. Generating the header does not copy the
tool's implementation into the product. If distributing build environments or
the tool itself, retain its license and provide its corresponding source too.

## Maintaining this bundle

When upgrading either MPL module, update the version, license, and SHA-256 pin in
`licenses/sources/modules.tsv` together. Archives are unmodified Go module proxy
ZIPs, including their original copyright notices. Verify them with
`go mod download -json` and the module's `go.sum` entry before updating.
`scripts/check-license-bundle.sh` checks the pins and notices without downloading
dependencies. `scripts/copy-licenses.sh DESTINATION` obtains the pinned archives
through Go's configured `GOPROXY` and module cache, verifies their SHA-256 hashes,
and includes them in the destination bundle. Offline packaging requires those
module archives to be present in the Go module cache beforehand. To verify a
packaged source directory, run `scripts/check-license-bundle.sh PATH/TO/licenses/sources`.

## Excelize algorithms in the Java Office source locator

- Component: `github.com/xuri/excelize/v2`, version `v2.11.0`; selected numeric, date and fraction algorithms are ported to Java.
- License: BSD 3-Clause, reproduced in [EXCELIZE-LICENSE.txt](backend-java/src/main/resources/META-INF/licenses/EXCELIZE-LICENSE.txt) and packaged in the Java jar at `META-INF/licenses/EXCELIZE-LICENSE.txt`.
- Copyright (c) 2016–2026 The excelize Authors; Copyright (c) 2011–2017 Geoffrey J. Teale.
- The native Go workbook formatter remains the compatibility oracle. The Java implementation documents unsupported formatting and archive capacity in [Office migration notes](backend-java/src/main/java/com/tencent/weknora/sourceloc/office-migration-notes.md).

## cppjieba and limonp in the Java retrieval segmenter

- Components: the C++ `cppjieba` and `limonp` sources and default dictionaries bundled with the locked `github.com/yanyiwu/gojieba v1.4.7` dependency.
- License: MIT; exact notices are included in [cppjieba-LICENSE](backend-java/src/main/resources/licenses/cppjieba-LICENSE) and [limonp-LICENSE](backend-java/src/main/resources/licenses/limonp-LICENSE), packaged under `BOOT-INF/classes/licenses/` in the executable Java jar.
- `backend-java/scripts/build-jieba.sh` compiles a Java JNI wrapper against those existing C++ sources and copies the notices beside the resulting native library and dictionaries. The deployed Java process executes no Go application code. Native binaries and dictionaries are explicit operator-provided assets.


nfp `v0.0.2-0.20250530014748-2ddeb826f9a9` is ported to native Java Excel number-format tokenization. Copyright (c) 2022-2025 Ri Xu. BSD 3-Clause, complete notice at `src/main/resources/META-INF/licenses/NFP-LICENSE.txt`. Excelize v2.11.0 generic formatting and generated LCID/month/weekday/era data share its existing BSD3 notice. Go1.26 UTC64bit calendar conversion uses the existing Go BSD3 license `GO-HTML-UNICODE-LICENSE.txt`.

## DuckDB in the Java data-table loader

- `org.duckdb:duckdb_jdbc:1.5.2.0` includes native DuckDB v1.5.2, source ID `8a5851971f`, matching this repository's current Go DuckDB backend.
- MIT, copyright 2018–2025 Stichting DuckDB Foundation. Original notices from the pinned upstream tags are included in [duckdb-jdbc-LICENSE](backend-java/src/main/resources/licenses/duckdb-jdbc-LICENSE) and [duckdb-LICENSE](backend-java/src/main/resources/licenses/duckdb-LICENSE).
- Excel loading additionally requires the matching DuckDB `excel` and `spatial` extensions. Extension loading and full summary-task integration are tracked in [the migration audit](backend-java/migration/datatable-summary-audit.md).
