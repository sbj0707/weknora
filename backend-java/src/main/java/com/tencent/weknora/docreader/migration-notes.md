# Native remote docreader adapters

Java generates `docreader.Docreader` and `docreader.DocReaderGrpc` from the canonical
`docreader/proto/docreader.proto` during Maven `generate-sources`. The protobuf source
is neither forked nor modified. Maven generation follows the
[gRPC Java build instructions](https://github.com/grpc/grpc-java#generated-code).
The Docker build must use the repository root:
`docker build -f backend-java/Dockerfile .` (image build not yet verified).

The primary `RemoteDocumentReader` bean selects the Go configured transport.
`DocReaderClient` implements the gRPC variant. Its
`DocumentReadRequest` preserves file bytes/name/type, URL/title, request ID,
parser engine and override map. `read` tries `ReadStream` first and falls back to
`Read` only on gRPC `UNIMPLEMENTED`, using the same deadline. Parser error strings
remain result data; transport errors remain `StatusRuntimeException` so task code
can classify their status. `listEngines` preserves file types, availability and
unavailable reason. Source mappings remain Unicode code point offsets, with the
same malformed/empty locator dropping and typed JSON rules as Go.

Existing environment variables apply:

| Variable | Meaning / default |
| --- | --- |
| `DOCREADER_ADDR` | remote address; unset leaves either adapter disconnected |
| `DOCREADER_TRANSPORT` | Go-compatible trimmed/lowercased `http`/`https` selects HTTP; all other values default to gRPC |
| `weknora.docreader.address` | Spring property overriding the address |
| `GRPC_TLS_ENABLED` | exact `true` enables TLS 1.2 or newer |
| `GRPC_TLS_CA` | PEM CA; unset uses default trust |
| `GRPC_TLS_CERT` / `GRPC_TLS_KEY` | PEM client pair for mTLS |
| `GRPC_TLS_SERVER_NAME` | certificate hostname / SNI override |
| `GRPC_AUTH_TOKEN` | `authorization: Bearer ...`; TLS-required credentials never travel over plaintext |
| `MAX_FILE_SIZE_MB` | send/receive per-message cap; default 50 MiB |
| `WEKNORA_DOCREADER_CALL_TIMEOUT` | positive Go duration; default 30m |

The explicit read timeout is capped by the configured timeout. An enclosing gRPC
`Context` can shorten that deadline or cancel the live call. As in the existing
Go deployment, explicitly disabling TLS permits bearer metadata over plaintext.
DNS uses the round-robin load balancing policy. `isConnected()` means a configured
channel, not a successful remote health probe. Close/reconnect shuts down the
old channel and rejects calls through a disconnected client.

Verification (2026-10-13, Asia/Shanghai):

- `mvn -f backend-java/pom.xml -Dtest=DocReaderClientTest,DocReaderSettingsTest,DocReaderTlsTest,GoDocReaderCompatibilityTest test`: 25 tests, no failures/errors/skips.
- Tests use real local TCP gRPC servers and verify file/config/metadata/image
  transfer, streaming beyond the total unary cap, per-frame limits, unary
  fallback, no fallback on authorization failures, shared deadline, parent
  cancellation, missing metadata, error preservation and engine discovery.
- Real local TLS/mTLS tests verify CA trust, hostname validation, mandatory
  client certificates and refusal to attach TLS-required tokens to plaintext.
- `go-docreader.json` is generated from production Go protobufs and
  `types.SourceLocator`: request/stream/unary fixtures plus 138 valid/invalid
  source mapping cases. JSON numbers are compared numerically, allowing 1/1.0.
- Regenerate: `go run ./backend-java/src/test/fixtures/docreader_fixture.go > backend-java/src/test/resources/go-docreader.json`.
- Live Java → Go interoperability passed 2 additional tests, using two local
  instances of the actual Go gRPC protocol, one plaintext and one with mandatory
  mTLS plus bearer authentication. Each covers streaming, legacy unary fallback,
  file bytes/overrides, metadata, image bytes, source mappings and engine discovery.
  Both test server processes were stopped after verification.

Reproduce the live interoperability check (three terminals; repository root):

```sh
go build -o /tmp/weknora-docreader-interop ./backend-java/src/test/fixtures/docreader_server.go
# Terminal 1: note the printed plaintext address.
/tmp/weknora-docreader-interop
# Terminal 2: note the printed TLS address.
/tmp/weknora-docreader-interop \
  --tls-cert backend-java/src/test/resources/docreader-tls/server.pem \
  --tls-key backend-java/src/test/resources/docreader-tls/server.key \
  --client-ca backend-java/src/test/resources/docreader-tls/ca.pem
# Terminal 3: substitute the two printed addresses, then stop both test servers.
mvn -f backend-java/pom.xml -Dtest=GoDocReaderInteropIT \
  -Ddocreader.interop.address=127.0.0.1:PLAINTEXT_PORT \
  -Ddocreader.interop.tls-address=127.0.0.1:TLS_PORT test
```

`GoDocReaderInteropIT` is excluded by normal Surefire discovery (`IT` suffix).
The normal Java test suite uses stored Go fixtures and local Java gRPC servers;
it needs no running Go/Python service. The live test uses a deterministic Go
server double, not real document/OCR engines.

## Native simple formats

`SimpleFormatReader` implements the shared `DocumentReader` interface alongside
`DocReaderClient`. It ports the production Go simple reader for Markdown/plain
text, CSV, JSON, original images and audio placeholders. CSV preserves Go
`LazyQuotes`, Unicode leading-space trimming, CRLF normalization and inferred
field counts. JSON preserves float64 values, Go JSON escaping/key ordering,
array normalization, nested paths and the existing semantic splitter limits.
Invalid UTF-8 replacement and MIME sniffing follow Go behavior. Image results
carry original bytes and the original-image flag; audio results preserve original
bytes and the audio flag for subsequent ASR. Audio transcription is not implemented.

Verification (2026-10-13): `GoSimpleFormatCompatibilityTest` passed 73 tests:
71 exact output/error fixtures generated by the real production Go reader plus
2 original-image/format-detection tests. The fixture cases cover Unicode,
malformed UTF-8, CSV quote/multiline/count errors, JSON scalar/array/nested/large
input/float overflow/escaping, MIME signatures, filenames, image bytes and audio
placeholders. The combined simple reader/gRPC command passed 98 tests with no
failures, errors or skips:

```sh
go run ./backend-java/src/test/fixtures/simple_reader_fixture.go > backend-java/src/test/resources/go-simple-reader.json
mvn -f backend-java/pom.xml \
  -Dtest=GoSimpleFormatCompatibilityTest,DocReaderClientTest,DocReaderSettingsTest,DocReaderTlsTest,GoDocReaderCompatibilityTest test
```

These are parser-level tests. Native upload/task selection, image storage,
OCR/ASR, chunking, embedding and indexing still require pipeline integration.

## HTML table normalization

`HtmlTableNormalizer` ports the production OCR Markdown wrapper around HTML table
conversion, using [jsoup](https://jsoup.org/download) for an HTML5 DOM and RE2/J
for the Go regular expressions. It excludes fenced code, pads table boundaries,
converts headers/rows/alignment/captions and inline formatting to Markdown,
normalizes escaped image references, and preserves real row/column spans with
the same stripped layout attributes and row newlines as Go. The Go converter's
nested-table behavior is retained for the covered inputs, including the wrapper's
first-closing-table regex behavior.

`GoHtmlTableCompatibilityTest` passed 70 exact production Go oracle cases, also
checking each fixture's second normalization against Go. Inputs cover styled,
ragged, malformed and nested tables, spans, empty rows, moved headers, alignment,
captions, code fences, adjacent blocks, Unicode, links/images/code/emphasis and
layout fallback. This verifies those cases, not the whole third-party Go HTML
converter. Other tag/DOM/escaping combinations still need differential coverage.

```sh
go run ./backend-java/src/test/fixtures/html_table_fixture.go > backend-java/src/test/resources/go-html-tables.json
mvn -f backend-java/pom.xml -Dtest=GoHtmlTableCompatibilityTest test
```

The normalizer is wired to native chunker preview. Native ingestion remains to
be wired, including image storage and downstream indexing.

## Parser configuration

`ParserConfiguration` ports knowledge/chat rule matching and defaults, all tenant
parser parameters emitted by `ParserEngineConfig.ToOverridesMap`, legacy MinerU
OCR-to-parse-method resolution, upload-over-tenant map precedence and the XLSX
first-row rule override. Knowledge rules preserve engine whitespace and reject
empty file-type matching; chat rules trim engines and allow an empty matching
type, as Go does. File types use Go Unicode whitespace trimming and per-code-point
lowercasing, including dotted I and context-independent Greek sigma. A supplied
native-office preference can replace the markitdown default for PPT/PPTX; the
default Java configuration has no linked office converter.

Four JUnit methods passed in the storage/parser/bridge isolated run (82 tests):
320 actual production Go rule/default outputs, 106 tenant override maps, XLSX
override gates, map copy isolation, nullable rules and typed config rejection.
The fixture calls real exported Go methods. This internal component does not
implement the provider registry, office/cloud readers, engine-list API or the
upload pipeline by itself.

```sh
go run ./backend-java/src/test/fixtures/parser_config_fixture.go > backend-java/src/test/resources/go-parser-config.json
mvn -f backend-java/pom.xml -Dweknora.build.directory=/tmp/weknora-java-parser-build \
  -Dtest=ParserConfigurationCompatibilityTest test
```

Not yet implemented/verified: wiring this adapter into native upload/Asynq task
processing, real Python parser/OCR deployment integration, HTTP/WeKnoraCloud
docreader transports, Go Anydoc conversion, image persistence/filtering, chunking,
embedding/indexing and system parser engine management routes. The adapter does
not claim that document ingestion or any of those routes is migrated. Streaming
avoids an aggregate gRPC frame cap, but the result currently retains all image
bytes in memory, matching Go's result aggregation; downstream streaming storage
requires a later pipeline change.

## Native HTTP/JSON transport

`HttpDocumentReader` posts `/read` and `/list-engines` with the production Go
JSON field names, omits empty optional fields, always sends `config`, and encodes
file/image bytes with standard base64. HTTP addresses are trimmed with Go Unicode
whitespace and lose one trailing slash; transport `http`/`https` prepends `http://`
when no scheme was provided, exactly as the Go container does. HTTP ignores gRPC
TLS knobs. Configured connection state is not a health probe; failed reconnect
validation preserves the prior address.

Constructor/reconnect input validation honors named whitelist entries without
requiring the service DNS record to already exist; dialing still validates DNS.
The shared native HTTP transport pins checked DNS answers to each socket, checks
HTTPS certificates/SNI, and rechecks configured SSRF policy before requests.
The fixed five-minute HTTP budget is shortened by enclosing gRPC Context deadlines;
parent cancellation closes in-flight I/O. Non-200 status/body errors and parser
error-as-data are preserved. Typed wire binding validates the first JSON value
before decoding, replaces malformed UTF-8 per byte, retains scalar and struct-slice duplicate/null
and metadata-map merge behavior, checks uint32 offsets (including rejecting negative zero) and byte-array/base64 types,
and reuses the canonical source-locator decoder. DTO nil collections normalize to
Java empty collections, consistent with the existing gRPC adapter.

Verification 2026-10-13: `HttpDocumentReaderCompatibilityTest` has six methods,
including 136 actual production-Go HTTP request/result/error fixtures, plus
container selection (HTTP ignores gRPC TLS flags), whitelist configuration before
DNS becomes available, SSRF/failed-reconnect/DNS pinning, live parent cancellation
and deadlines, and checked same-origin redirects. A clean isolated Maven run of
HTTP6 + existing gRPC25 + parser-config4 + JSON scanner1 + bridge5 passed all41,
with zero failures/errors/skips; `/tmp/weknora-java-docreader-http-expanded-test.log`.

Regenerate fixtures from the repository root:

```sh
go run backend-java/src/test/fixtures/docreader_http_fixture.go /tmp/go-docreader-http.json
cp /tmp/go-docreader-http.json backend-java/src/test/resources/go-docreader-http.json
mvn -f backend-java/pom.xml -Dweknora.build.directory=/tmp/weknora-java-docreader-http-build \
  -Dtest=HttpDocumentReaderCompatibilityTest,DocReaderClientTest,DocReaderSettingsTest,DocReaderTlsTest,GoDocReaderCompatibilityTest,ParserConfigurationCompatibilityTest,GoJsonFirstValueCompatibilityTest,GoCompatibilityBridgeTest clean test
```

Remaining transport differences: shared native HTTP rejects cross-origin redirects,
URL userinfo and configured proxy transport; Go's safe client can handle some of
these. Network/SSRF/deadline exception wording is not yet byte-identical. Java
`close` releases its transport resources, whereas Go HTTP Close is a no-op. HTTP response bodies currently follow Go's unbounded read behavior.
This is a parser transport foundation; provider reader selection/availability,
Anydoc/Office/OCR/cloud readers and public upload/processing/index pipelines still
need migration. No public business route is declared complete by this adapter.

## Reproduce parser discovery, health probes and settings schema

`backend-java/scripts/generate-parser-oracles.py` runs the five checked-in standalone
Go oracle sources against the production Go implementations. It validates 108
registry cases, 408 loopback probe cases, 18 redirect cases and 121 system parser
handler cases, plus the reflected `ParserEngineConfig`/`CredentialsConfig` schema.
The Cloud probe changes only the `defaultBaseURL` constant through a temporary
Go overlay, points it at an independently allocated loopback port, and supplies
that same port as `WEKNORA_FIXTURE_PORT`; production Go files remain untouched.
The script extracts the final complete JSON after Go logger output and validates
all five outputs before writing any fixture. Fixture JSON defaults to test
resources and schema JSON to main resources. An explicit `--output-dir` redirects
all five outputs, so review and reproduction do not overwrite checked-in data.

```sh
python3 backend-java/scripts/generate-parser-oracles.py --dry-run --output-dir /tmp/weknora-parser-oracles
python3 backend-java/scripts/generate-parser-oracles.py \
  --output-dir /tmp/weknora-parser-oracles \
  --compare-to backend-java/src/test/resources \
  --log-dir /tmp/weknora-parser-oracle-logs
```

`--compare-to` compares fixture JSON semantically; schema comparison defaults to
main resources and can be overridden with `--schema-compare-to`. On 2026-10-13,
all 655 cases and the typed schema reproduced in `/tmp` and matched the checked-in
JSON semantically. Evidence: `/tmp/weknora-parser-oracle-reproduction.log` and
`/tmp/weknora-parser-reproduced-logs/`. This replays deterministic Go parser
registry/probe/handler behavior with loopback servers, not real external OCR or
Cloud deployments.

## Native system parser routes

`SystemParserController` owns only `GET /api/v1/system/parser-engines`,
`POST /api/v1/system/parser-engines/check` and `POST /api/v1/system/docreader/reconnect`.
The optional native bridge selector includes these exact methods and paths.
The read requires Viewer; both POSTs require Admin before request-body binding.
Scoped tenant API keys require `manage_vector_stores`, and full tenant keys retain
the Go role bypass. Other system management operations remain in Go.

Registry ordering, capitalized Go DTO fields, local availability, last-wins local
metadata, duplicate remote-only engines, and null versus empty remote file-type
lists are retained. Check binds the locked Go configuration schema, preserves
saved secrets for empty or `***` input and preserves nil parser rules; it does
not save settings. Go's SQLite config scanner accepts byte slices, so BLOB and
TEXT values remain distinct. Reconnect changes the shared native remote reader
and reports the configured transport, without treating configuration as a live
health guarantee. Discovery failures fall back to the static registry.

Four new differential methods cover 108 registry, 408 real loopback HTTP probe,
121 actual Go SystemHandler HTTP response and 18 real HTTP redirect traces. The
handler oracle uses production Go SQLite schema with fixture identity and a
controlled RemoteDocumentReader SPI. Separate full Spring role/API-key tests
exercise real AuthFilter and denied-role auditing; packaged HTTP checks are
recorded in the root VALIDATION.md after completion. Existing HTTP reader wire
cases and gRPC discovery now additionally assert Go nil-file-types semantics.

The parser probe transport allows checked GET/HEAD cross-origin redirects while
stripping headers except Accept, Accept-Language and User-Agent; original POST
requests still cannot cross origins after a 303 method change. Every hop repeats
DNS validation. HEAD remains HEAD, body headers are removed after 301/302/303,
and the five-request redirect cap matches the Go oracle. The existing model and
remote-reader constructors retain their cross-origin rejection policy.

Limitations: native in-process Anydoc conversion and complete cloud/OCR parser
selection remain unfinished. The default registry preserves the actual Go stub
unavailable metadata; Go build instructions in that message are not Java build
instructions. Go and Java network/SSRF/proxy failure strings are not byte-identical,
and configured HTTP proxy support remains unimplemented. Java's existing AuthFilter
rejects unknown membership roles before the route guard, unlike the Go guard's
role-denial sequence. These routes remain in_progress.
