# Native model calling SPI

This package supplies internal Java callers for five remote Go wire protocols:
`openai-embeddings`, `cohere-rerank`, `openai-completions`, `openai-responses`, and
`anthropic-messages` (all three remote chat protocols include SSE), plus source-local
Ollama embeddings and chat/NDJSON.
`ModelClientFactory.embedding/rerank/chat(AuthContext, modelID)` resolves the
current catalog generation, reads the tenant-visible active persisted model,
and strictly decrypts Go `enc:v1:` credentials before constructing a client.
This package itself registers no public inference, indexing, RAG, connectivity or
probe route. Indexing callers can use this SPI; their implementation and route
status are documented separately. Public inference/probe routes remain on the Go side unless separately documented
as native; model management routes have their own migration status.

The shared HTTP/1.1 transport uses the existing shaded Netty dependency. It pins
the exact validated DNS answers, checks every redirect before dialing, blocks
cross-origin request replay, keeps the hostname for HTTP Host and TLS SNI and
certificate hostname verification, uses the platform trust store, handles gzip,
and closes outstanding connections on deadline/cancellation. Transport errors
do not expose URL/header/credential values. Vendor error bodies are bounded and
configured API/app/extra secrets are redacted. Per-message response and SSE line
limits prevent unbounded body buffering. There is currently no connection pool
or HTTP/2, which changes throughput rather than the wire protocol.

Outbound whitelist settings use the live system setting with environment
fallback, `SSRF_WHITELIST_EXTRA`, wildcard/CIDR rules, whitelist-only denial
before DNS, and the migrated Go address classification. A named whitelist
deliberately bypasses private-address restrictions like Go. Unlisted DNS
answers are checked both at URL validation and immediately before socket dial.
HTTP(S) proxy tunneling is not migrated: when Go environment proxy selection
would require a proxy, native calls fail explicitly instead of silently making
a direct request. Go `NO_PROXY` hostname/IP/CIDR/port and localhost/loopback
exemptions can still use direct transport. No proxy URL or credentials are
included in the exception.

Embedding honors vendor request fields, configured dimension override,
Unicode batching, positional responses without index, explicit reverse index
placement, and rejects duplicate/missing/empty/out-of-range vectors. Only
transport errors use Go's three retries/backoff; HTTP and decoding failures do
not retry. Rerank honors vendor top_n/return_documents/truncation options,
Unicode query/document/request budgets, bounded batch concurrency, batch index
offsets, stable descending scores and configured logit-to-probability conversion.

Chat request construction includes the actual catalog's sampling/token/tool
and response-format settings, reasoning level normalization and clamping,
special-token neutralization, prompt cache/affinity settings, supported
reasoning replay and tool metadata. Nonstream responses normalize the Go DTO
and cache accounting. SSE parses Go's individual `data:` lines, emits thought,
answer and tool events, assembles gapped tool indices and argument fragments,
retains reasoning metadata/usage, and marks premature EOF as `incomplete`.

Explicit remaining gaps: signed WeKnoraCloud authentication; other embedding,
rerank and chat protocols; local model administration and signed Ollama authentication; storage-backed image URL
resolution; Go raw packet
debug dumps and precise provider-specific error wording. Absolute HTTP/data
image URLs are supported as remote-provider request content. The source-local
Ollama client fetches allowed HTTP images as described below. Requests that need storage image resolution or an unsupported protocol
fail explicitly. The common assembler now ports Go incremental `thought` extraction and sandbox
write/edit progress (path, UTF-8 byte count, line counts, ten-line preview and
120 ms throttling). Progress reports describe a prospective tool call; this SPI
does not write sandbox files. Consumer callbacks must cooperate with cancellation
when they block; checks run before and after each callback.

Validation sources are the repository's actual Go factories and request
builders, not hand-authored vendor capability tables. Reproduce with:

```
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/client_fixture.go /tmp/go-client-fixtures.json
```

The fixture generator uses a local `httptest` server and synthetic credentials.
The committed snapshot contains 27 Go HTTP request/response cases, 189 Go
provider/thinking request bodies, 9 Go embedding placement cases and 15 Go
Unicode batching cases. Java tests call an actual loopback HTTP server with
AES-encrypted persisted credentials and compare both wire data and returned
DTOs. Additional tests cover tenant/builtin isolation, denied credentials and
protocols, DNS pinning/rebinding/mixed answers, redirect credential isolation,
large chunked/gzip bodies, timeouts/cancellation, untrusted TLS rejection using
the actual Go `httptest` server certificate, proxy fail-closed selection,
batch concurrency/stability, malformed replies and broken/cancelled SSE.
The original three client test classes contain 14 JUnit test methods; the
Responses/helper expansion below adds six more.


Responses migration adds ordered instructions/input conversion, replay of raw
reasoning and function-call item metadata, image/text input parts, structured
JSON suffix placement on the last user text, minimum output-token budget,
reasoning/encrypted-content options, cache affinity, and Azure Responses endpoint
selection. Native decoding joins message/summary output, normalizes usage/cache
accounting and retains raw reasoning artifacts. SSE correlates calls by item ID
or output index, accepts arguments emitted only on item.done, handles terminal
completed/incomplete/failed events, falls back to final reasoning items, and
marks premature EOF/DONE as incomplete. Known typed envelope/output fields are
validated even when they are not used by that event; unreadable final reasoning
fallback items are deliberately tolerated like Go.

Additional production Go oracle sources:

```
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/responses_fixture.go /tmp/go-responses-fixtures.json
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/stream_fixture.go /tmp/go-stream-fixtures.json
```

Responses tests cover 189 catalog/thinking request builders and 25 real HTTP
request/response/SSE cases, including replay metadata, Azure URL/auth, partial
completion, usage/cache, parallel/no-ID tools, thinking JSON, sandbox progress,
failed events and malformed final reasoning artifacts. Shared helpers compare
52 extractor fragmentation cases, 12 write/edit partial-JSON progress cases and
7 complete assembler event sequences directly with the production Go outputs;
a separate clock-controlled test verifies byte throttling versus immediate
line-count emission. The five original client test classes contain 20 JUnit methods; the subsequent
fallback/Unicode-header class below adds two more.
Build in an isolated output directory to avoid editor-generated stale classes:

```
mvn -q -Dweknora.build.directory=/tmp/weknora-java-models-build -Dtest=NativeModelClientsTest,ModelTransportSecurityTest,ModelClientResponsesTest,OpenAIResponsesParityTest,GoStreamHelpersParityTest,ChatFallbackParityTest clean test
```

Remaining constraints include Java integer bounds for provider token/index
fields in the older protocols, precise Go parser error text outside the Anthropic typed binder, storage image resolution and the listed
protocol/runtime/proxy gaps. Passing these fixtures does not prove all model
protocols, public API routes or end-to-end RAG have been migrated.


The completions and Responses clients now use Go's single image-stripped retry
when a typed provider HTTP error mentions images/multimodal/vision and either
explicitly says unsupported/not supported or has status 400. An unrelated
400 and an image-size 500 do not retry. The retry clears both `images` and
image-bearing `multi_content`, preserves text/fallback content and caller
messages/options, and does not retry response decoding failures. `CallContext`
can carry the session ID for Go's context-derived prompt cache key; an explicit
option wins, retention `none` suppresses the body cache fields while preserving
Go affinity headers, and keys are clamped to 64 Unicode code points.

The HTTP transport preserves UTF-8 header bytes like Go rather than allowing
Netty's default ASCII encoding to alter Unicode cache/affinity/custom headers.
Header controls remain rejected before sending. The additional Go oracle
`chat_fallback_fixture.go` covers 32 scenarios with 52 actual loopback HTTP
requests across both chat protocols, stream/nonstream calls, status-based retry,
explicit/context cache keys, disabled/long retention and Unicode key clamping.
The generator asserts it actually reached the loopback provider for every case.
Java compares each request body/header and resulting DTO/event sequence, checks
that input messages/options are not mutated, and separately checks UTF-8 custom
header bytes and CRLF rejection. This expansion contains
22 methods across six client test classes; the Anthropic class below adds two more.

```
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/chat_fallback_fixture.go /tmp/go-chat-fallback.json
```

This closes the previously listed image-retry and context-session cache gaps;
other protocol/runtime/storage-image and precise-error limitations still apply.


Anthropic Messages migration resolves the live catalog's native protocol, auth,
root/v1/v1beta/full-messages URL, required version and ordered beta headers.
Request building ports budget/adaptive thinking and effort clamps, tool choice
and parallel controls, cache markers/TTL, system/user multimodal blocks and
adjacent tool-result merging. Signed thinking and redacted blocks are replayed
per block; unsigned thinking is omitted and legacy signatures from another
protocol are excluded. Stored signed blocks are preserved without changing
their text. HTTP/data image references remain subject to the storage-resolution
limitation above, and the shared single image-stripped retry is used.

Native nonstream decoding and streaming retain raw tool-input JSON spelling,
block signatures/metadata, gapped 64-bit block indices and token counters,
cache accounting, cumulative usage, EOF/error semantics and tools without
argument deltas. A nonstream request can collect an SSE response like Go. The
typed envelope binder preserves Go null/duplicate-field behavior, folded field
names and the first type error even when a later duplicate repairs the value;
fixture assertions compare complete Go parser/type-error messages. This is
internal calling SPI parity, and does not migrate public inference/RAG routes.

The production Go oracle contains 558 request builders (catalog/thinking and
message/options matrices) and 82 real loopback HTTP/SSE cases. Cases include
signed thinking/replay, redacted blocks, raw JSON tool arguments, cache usage,
URL/auth/header variants, reordered/truncated tools, typed duplicate/null/case
aliases, malformed envelopes, 64-bit counters/indices and SSE-to-chat collection.
The generator and native HTTP tests use synthetic encrypted credentials.

```
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/anthropic_fixture.go /tmp/go-anthropic.json
mvn -q -Dweknora.build.directory=/tmp/weknora-java-models-build -Dtest=NativeModelClientsTest,ModelTransportSecurityTest,ModelClientResponsesTest,OpenAIResponsesParityTest,GoStreamHelpersParityTest,ChatFallbackParityTest,AnthropicMessagesParityTest clean test
```

The isolated `clean test` passes all 24 methods in seven client test classes
with zero failures, errors or skips. Other protocol/runtime/proxy/storage-image
gaps and the public-route boundary above remain.


Source-local Ollama calling uses the production Go source-local constructors
and the locked Ollama v0.23.2 SDK as its oracle. The factory still checks the
tenant-visible model, capability and active status, and maintains strict stored
credential decryption. As Go does, local calls ignore the persisted base_url
and use the shared OLLAMA_BASE_URL (default http://localhost:11434);
OLLAMA_OPTIONAL=true preserves the initial optional-service behavior. A shared
availability state reproduces HEAD heartbeat, tags lookup, missing-model pull
and subsequent request checks, including the double tags/heartbeat sequence
inside pull. Model download management and public local-model routes remain
separate migration work.

Native /api/embed requests port name/default truncate, num_ctx, positive
dimension override and ignored query/text distinctions. Numeric JSON is parsed
directly into float32 so decimal midpoint, subnormal underflow and negative-zero
bits match Go, rather than introducing double-to-float rounding. Java deliberately
rejects three fixture shapes that the Go SDK accepts: mismatched vector count,
null embeddings and empty vectors. Those are separate stricter-validation
assertions, not error-parity claims. Expected configured vector dimension is
validated by indexing callers; this client validates consistent nonempty rows.

Chat ports message neutralization, text/image concatenation, user-only images,
tool-name/index conversion, ordered tool arguments and Go typed tool-schema
projection. NDJSON supports nonstream last-frame content/tools/positive usage
behavior, thinking-to-answer events, thinking-tool events and full tool calls.
Go EOF and thinking-only termination are preserved without inventing terminal
events. Both model requests and HTTP images reuse the validated/pinned transport,
whitelist, TLS, redirect, proxy fail-closed and caller cancellation/deadline rules.
HTTP images retain Go's 20 MiB truncated prefix behavior, including readable
non-200 image bodies. Stream lines are capped at 8 MiB and model responses at
64 MiB. The stricter shared egress checks apply to local calls too; operators
must explicitly allow private Ollama destinations.

Explicit limitations: signed Ollama auth (OLLAMA_AUTH or ollama.com) fails before
provider traffic; storage/local-resource image resolution and local rerank are
not implemented. The Java User-Agent identifies Java and differs from the Go
SDK/runtime User-Agent. The fixture checks Accept, Content-Type and Authorization,
not User-Agent or transport timing. The response typed-binder expansion below ports folded keys, duplicate/null
behavior, unused response fields and tested floating-point tool formatting.
Other vendor SDK binders and untested timestamp syntax remain separate work.
This stage does not establish full SDK or public inference/RAG route parity.

The production Go generator records 78 actual HTTP/NDJSON cases and 355 requests,
including optional/repeated availability, pull/recheck, base prefixes/query,
request sampling/reasoning/tools/images, NDJSON termination and error frames,
status/preflight errors, HTTP image redirects, float32 bits and bad vector shapes.
Three JUnit methods compare request bodies/paths/headers, DTOs/events and exact
float32 bits; separate assertions cover tenant/status isolation, signed-auth
rejection, egress denial, cancellation and expired caller deadlines.

```
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/ollama_fixture.go /tmp/go-ollama.json
mvn -q -Dweknora.build.directory=/tmp/weknora-java-ollama-build -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib -Dtest=OllamaClientsParityTest,SQLiteIndexGoParityTest,NativeModelClientsTest,ModelTransportSecurityTest,ModelClientResponsesTest,OpenAIResponsesParityTest,GoStreamHelpersParityTest,ChatFallbackParityTest,AnthropicMessagesParityTest clean test
```

The SQLite library path above is operator-provided and platform-specific; build
it using scripts/build-sqlite-vec.sh first. The targeted command covers 31 methods
in nine classes (the original 24 client methods, four SQLite methods and three
Ollama methods). The isolated clean targeted run passed all 31 methods in
nine classes with zero failures, errors or skips.


The Ollama response binder now consumes tokens in source order, so an invalid
first assignment remains an error even if a later duplicate repairs the value.
It matches Go field folding (including long-s/Kelvin aliases and excluding
dotless-i), merges ordinary repeated structs, resets the custom Message on
each assignment, distinguishes missing from explicit null tool arguments,
and retains the first ordinary type error while propagating fatal custom
decoder errors. Typed decoding covers all locked ChatResponse, EmbedResponse,
ListResponse/ListModelResponse/ModelDetails, ProgressResponse, error-envelope,
StatusError/AuthorizationError fields, including unused timestamp, debug-info,
image byte arrays, duration, logprob and token-byte fields.

Time fields are validated for the tested Go RFC3339/fallback forms, then omitted
from the calling SPI as before; they are not offered as a public SDK DTO. The
SDK oracle zeroes those unused timestamps before comparing normalized structs.
Tests retain SDK error text for invalid dates/non-string times and Go's accepted
single-digit-hour/comma-fraction/24-hour-zone edge forms. They are not proof of
every Go time.Parse layout corner. Base64 errors use Go byte offsets. Message
role normalization reuses the generated Go Unicode ToLower resource and its
existing license. Unpaired string surrogates become U+FFFD, while invalid
ordered-map keys retain the SDK custom error.

Tool argument JSON retains top-level insertion order, uses UTF-8/code-point
ordering for nested map keys, preserves negative zero, and emits the shortest
round-tripping float64 decimal with Go's fixed/exponent thresholds. The oracle
includes extreme subnormals and 128 deterministic random float64 bit patterns.
The shortest-decimal implementation is Java, validated against Go outputs; no
Go runtime or business bridge is used. Ignored decoder errors on outgoing tool
arguments retain the preceding successfully decoded keys like the Go caller.

The locked production SDK json.Unmarshal oracle contains 1,498 cases covering
malformed JSON, root types, null/missing/duplicate/folded fields, numeric bounds
and precise nested first errors. A separate production Go client oracle contains
236 actual HTTP/NDJSON cases and 987 requests (including the original 78 cases),
checking normalized chat/events/vectors and HTTP-status decoding. Four cases
exercise the three stricter Java vector-shape rejection categories previously
documented; those are intentional validation differences, not error parity.
Two new JUnit methods add these SDK/wire comparisons to the previous suites.

```
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/ollama_binder_fixture.go /tmp/go-ollama-binder.json
go run ./backend-java/src/test/java/com/tencent/weknora/model/client/ollama_binder_wire_fixture.go /tmp/go-ollama-binder-wire.json
mvn -q -Dweknora.build.directory=/tmp/weknora-java-ollama-binder-build -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib -Dtest=OllamaTypedBinderParityTest,OllamaBinderHTTPParityTest,OllamaClientsParityTest,SQLiteIndexGoParityTest,NativeModelClientsTest,ModelTransportSecurityTest,ModelClientResponsesTest,OpenAIResponsesParityTest,GoStreamHelpersParityTest,ChatFallbackParityTest,AnthropicMessagesParityTest clean test
```

The 11-class/33-method isolated clean targeted command passed all 33 methods
with zero failures, errors or skips. Other vendor binders, signed authentication, proxy transport,
storage image resolution, local administration and public inference/RAG routes
retain their separately documented migration status. SQLite validation does
not establish native Postgres/ParadeDB retrieval support.
