# Native chunker migration

`TextSplitter.splitText` ports `internal/infrastructure/chunker/splitter.go`'s
legacy path and `header_tracker.go`. It preserves priority-recursive separators,
protected Markdown links/images, math, code and table rows, the 7500-rune absolute
limit, semantic overlap priorities and lookbehind, table-header reuse/empty-header
extension/table boundaries, and source offsets. `splitTextParentChild` retains
only parents whose content is actually subdivided and gives children global
sequence numbers and additive source offsets. `Chunk.embeddingContent` preserves
Go Unicode whitespace trimming. `SplitterConfig.normalized` mirrors base Go
normalization. Patterns use [RE2/J](https://github.com/google/re2j), matching Go's
RE2 semantics. A standard Java regex reproduced `StackOverflowError` on a table
row containing 16,000 pipes; RE2/J passed the corresponding production Go
fixtures, including the unterminated variant and repeated wide-table rows.

Chunk positions use Unicode code points, corresponding to Go rune offsets.
Legacy table header synthesis intentionally matches Go, including generated
header text not occupying source positions; this means the simple
`end-start == content rune count` invariant cannot be asserted for every legacy
table output. `extractImageRefs` unwraps linked images and supports one level of
balanced URL parentheses. Its offsets intentionally use UTF-8 bytes in the
unwrapped text, matching Go's actual regexp API, not the chunk offset convention.

Verification (2026-10-13): `GoChunkerCompatibilityTest` passed 254 tests, no
failures/errors/skips. Each compares exact content, context, sequence, offsets,
embedding content, full parent-child results and extracted image references
against actual production Go outputs. There are 154 deterministic document/config
combinations (including empty, multilingual/emoji, CRLF, protection, tables,
large atomic units and pathological overlap) plus 100 deterministic random
cases. The oracle is compressed to avoid storing repeated large documents.

```sh
# Repository root. Capture only the JSON produced by the Go generator.
go run ./backend-java/src/test/fixtures/chunker_fixture.go > /tmp/weknora-go-chunker.json
python3 - <<'PY'
import gzip
from pathlib import Path
source = Path('/tmp/weknora-go-chunker.json').read_bytes()
Path('backend-java/src/test/resources/go-chunker.json.gz').write_bytes(gzip.compress(source, mtime=0))
PY
mvn -f backend-java/pom.xml -Dtest=GoChunkerCompatibilityTest test
```

`AdaptiveChunker` now ports the complete Go strategy chain: document profiling,
heading/heuristic/legacy selection, validation and rejection reasons, language
and token estimation, token budgets, overlap clamping, heading stack and deep
breadcrumbs, tiny-section coalescing, protected heuristic boundaries, aligned
overlap, strategy-aware parent/child splitting and ingestion config derivation.
Line-ending normalization is available to pipeline callers. An empty stored
strategy retains Go's legacy behavior; `auto` and unknown strategies profile
the document before selecting tiers.

An additional `GoAdaptiveChunkerCompatibilityTest` passed 175 actual production
Go cases covering seven strategies on 25 documents. Each compares chunks,
embedding content, full parent/child outputs, profiles, language labels and
both diagnostic traces (selected tier, chain, rejections and optional profile).
Combined legacy/adaptive verification passed 429 tests, zero failures/errors/skips:

```sh
go run ./backend-java/src/test/fixtures/adaptive_chunker_fixture.go > /tmp/weknora-go-adaptive-chunker.json
python3 - <<'PY'
import gzip
from pathlib import Path
source = Path('/tmp/weknora-go-adaptive-chunker.json').read_bytes()
Path('backend-java/src/test/resources/go-adaptive-chunker.json.gz').write_bytes(gzip.compress(source, mtime=0))
PY
mvn -f backend-java/pom.xml -Dtest=GoAdaptiveChunkerCompatibilityTest,GoChunkerCompatibilityTest test
```

## Native preview and HTML normalization

`ChunkerPreviewController` implements the exact `POST /api/v1/chunker/preview`
route. It retains the 65,536 Unicode-code-point input limit, line-ending and HTML
normalization, config defaults, adaptive/parent-child diagnostics, full-set
statistics and the 500-result truncation. It starts the five-second deadline
before decoding and waits for computation using the remaining budget. The CPU
splitter does not accept cancellation, as in Go; timeout returns 504 while the
computation may finish in the background. The route requires a viewer or a tenant
API key with full, retrieve or ingest capability, and performs no knowledge/chunk
writes or embedding calls.

`ChunkerPreviewIntegrationTest` passed 91 tests: 90 actual production Go handler
responses plus HTTP authentication/tenant/method checks, using the real Go SQLite
schema and signed tokens. Every fixture compares all JSON fields, ordering and
numeric values and checks that knowledge/chunk tables remain empty. Combined with
70 actual-Go HTML normalizer tests and one scanner test, the run passed 162 tests
without failures, errors or skips (`/tmp/weknora-java-preview-syntax-test.log`).
The scanner test compares 7,039 production Go `encoding/json.Decoder` byte inputs,
including exact first-value boundaries, malformed tokens/escapes, all byte values,
EOF and the 10,000-level nesting limit. Preview now preserves precise Go syntax
errors, accepts trailing content after the first value, validates syntax before
field types, replaces invalid UTF-8 per byte and folds Go case aliases. The 90
HTTP fixtures store base64 request bodies to preserve malformed UTF-8. Further
HTML converter and HTTP failure modes still need coverage. Regenerate the HTTP oracle:

```sh
go run ./backend-java/src/test/fixtures/chunker_preview_fixture.go > /tmp/weknora-go-chunker-preview.json
python3 - <<'PY'
import gzip
from pathlib import Path
Path('backend-java/src/test/resources/go-chunker-preview.json.gz').write_bytes(
    gzip.compress(Path('/tmp/weknora-go-chunker-preview.json').read_bytes(), mtime=0))
PY
mvn -f backend-java/pom.xml -Dweknora.build.directory=/tmp/weknora-java-preview-build \
  -Dtest=GoJsonFirstValueCompatibilityTest,ChunkerPreviewIntegrationTest,GoHtmlTableCompatibilityTest test
```

Remaining migration: upload/task/embedding/repository integration, broader HTML
conversion and complete HTTP compatibility verification. The existing Go
implementation remains intact, and route status stays `in_progress`.

Regenerate the scanner oracle independently:

```sh
go run ./backend-java/src/test/fixtures/json_first_value_fixture.go > /tmp/weknora-go-json-first-value.tsv
python3 - <<'PY'
import gzip
from pathlib import Path
Path('backend-java/src/test/resources/go-json-first-value.tsv.gz').write_bytes(
    gzip.compress(Path('/tmp/weknora-go-json-first-value.tsv').read_bytes(), mtime=0))
PY
```
