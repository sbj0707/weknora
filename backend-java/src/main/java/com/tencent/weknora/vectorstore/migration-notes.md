# Bound vector-store / ES8 migration slice

This is a partial native migration, not a claim that all vector stores or background
publication support are interchangeable with Go. The previously accepted immutable
7538-test / 154-class / 36-process checkpoint predates these new classes.

## Native implementation

`VectorStoreMetadata` reads the live store by effective KB owner, ID and nondeleted
state. Generated Go struct schemas preserve typed `ConnectionConfig` / `IndexConfig`
decoding, including duplicate keys, nulls and field defaults. Credentials use strict
Go-compatible decryption; error responses do not echo credentials or endpoints.
The Go SQLite Scanner's []byte-versus-string behavior is retained; PostgreSQL JSONB
is exercised separately.

`VectorStoreRegistry` verifies ownership even before a cache hit; shares one detached
10-second cold build per owner/store, lets each caller leave independently, applies
30-second construction-failure cooldown, and fences late publication across explicit
register/unregister. Store ownership changes and tombstones cannot bypass the cache
check. A nil factory result and recovered factory panic follow their distinct Go
cooldown behavior. Owned clients are closed when the application exits.

`Elasticsearch8Store` implements actual REST bulk writes, cosine and keyword retrieval,
metadata filters, missing historical `is_enabled`, both keyword-field mapping shapes,
and chunk/source/document deletes. `HybridSearchService` partitions by effective
owner and bound store, uses that store's capabilities, and retains original caller
permissions and model/hydration authorization. A bound empty or missing engine never
falls back to a populated default index. Its HTTP context receives the same group
budget used for SQL. All-bound requests do not require a registered default engine.

The existing Go SDK bulk implementation ignores individual item errors; this adapter
also preserves that behavior. A successful HTTP bulk call therefore does not establish
that every item was indexed. Backend-generated IDs are checked as nonempty and omitted
only from cross-process physical oracle comparisons; all other hit fields and raw
score bits are compared. Public response arrays and nested JSON strings are not sorted
or normalized to hide differences.

## Actual Go baseline correction

A real blocked HEAD request through production `createEngineServiceFromStore` proved
that Go ES8 construction discarded the registry build context: both deadline and cancel
samples returned an engine after three HTTP requests even though the context had ended.
The original evidence remains in `/tmp/weknora-go-vector-store-es-context-before.json`
and its log. The additive context-aware ES8 constructor and production factory now
pass the context through HEAD/create/mapping and return its error after initialization.
Old direct constructor signatures remain available. Ordinary non-context init failures
retain their previous log/defer behavior. ES7 is not changed or enabled in Java.

The safe actual Go two-case fixture returns no engine, a context error, and only one
HTTP request. Native tests replay these decisions using real blocked HTTP. Go's sample
uses a 30ms deadline and Java uses 300ms to avoid JVM startup timing sensitivity; this
proves cancellation decisions and request count, not equal elapsed time.

## Validation at this slice

All ES evidence uses the already available local image
`docker.elastic.co/elasticsearch/elasticsearch:8.14.0`, an owned temporary loopback
container, and unique per-run indexes. It does not use a development volume or delete
an unrelated index.

* Real production Go ES repository: two mapping groups, each 12 queries and three
  deletes; actual counts 4→3→2→1. Native `Elasticsearch8GoParityIT`: 2 tests.
* Actual Go public Gin handler/middleware, model HTTP, store factory, live metadata and
  physical ES: 52 scenarios, including normal/shared/Agent/API scopes, two stores,
  two owners, absent/foreign/deleted/corrupt/unsupported stores and default-engine
  override. Native SQLite replay: 52 tests.
* Generated Go metadata Scanner/strict credentials: 69 native cases. Actual Go registry
  lifecycle: 13 native cases. Registry instrumentation is not an ES physical test.
* Production Spring/JWT/API-key/Viewer/Hikari1 + actual ES: 7 tests, including deletion
  staying empty while a populated default SQLite index remains available.
* Combined command selected the above plus existing 143 public hybrid, 6 Spring,
  3 deadline, 3 fanout and 2 blocked constructor cases: **300 tests / 10 classes,
  zero failures/errors/skips, process exit 0**. Reports:
  `/tmp/weknora-java-vector-store-combined-second-build/surefire-reports`; log:
  `/tmp/weknora-java-vector-store-combined-second.log`.
* Independent PostgreSQL runner applied all 115 Go migrations, used Hikari pool size 1
  and real ES8: **52 tests, exit 0**. Reports:
  `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-vector-hybrid-c_789r98/build/surefire-reports`;
  log `/tmp/weknora-java-vector-hybrid-postgres-final.log`.
* Current Go affected packages and complete `go test -tags sqlite_fts5 ./...` both
  exited 0 after the constructor fix; full log
  `/tmp/weknora-go-vector-store-full-final.log`.

The final Go constructor-fixed SQLite oracle was regenerated to
`/tmp/weknora-go-vector-store-hybrid-final.json`; all 52 response bodies, statuses
and provider captures are strictly unchanged. PostgreSQL was also regenerated and
replayed after that production fix.

Earlier failures are preserved: combined first encountered another concurrently edited
test's missing token helper; metadata first exposed exact integral JsonNode assertion
types; PostgreSQL first had a fixture count assertion of 50 after the oracle expanded
to 52. No production behavior was relaxed to pass those assertions. A final fixture
regeneration was first invoked without its required output-path argument and failed
at export; that failed log is retained separately from the corrected invocation.

Use `backend-java/scripts/postgres-vector-store-hybrid-parity.py --es-url ...
--jieba-library ... --jieba-dict ...` for the owned PG replay. Use
`backend-java/scripts/vector-store-hybrid-smoke.py JAR VEC --es-url ...
--topology single|go` for the immutable packaged public path. This runner seeds its
own engine, verifies exact Go public DTOs, physical deletion, missing-store errors,
and explicit unbind/default behavior. Tasks are disabled in that synchronous runner;
its topology flag is configuration coverage, not background worker parity.

## Explicit remaining limits

* Store CRUD/public credential projections, persistent update/delete producers and
  startup eager registration are not migrated. This slice implements owner-scoped
  metadata consumption and the lazy runtime registry.
* Bound document/manual publication, bound index cleanup workers, index-status and
  chunk index-detail adapters remain gated/unported. Direct native ES writer/delete
  components are tested, but that does not enable those entire producer chains.
* ES7, OpenSearch, Qdrant, Milvus, Weaviate, Doris and Tencent stores are not implemented.
  Only the local pinned ES8 version has physical engine evidence. Imported legacy
  index names outside the adapter's ASCII allowlist are rejected.
* Redirect/TLS/authentication failure matrices, actual blocked public ES search groups,
  backend rejection diagnostics, unavailable nodes, mapping migration, payloads beyond
  the response size limit and multi-node operation have not been proven equivalent.
  Constructor blocking, registry cancellation and existing SQL group cancellation are
  separate verified boundaries and must not be generalized to these missing cases.
* No production cutover, Go removal, public chat/SSE migration or global verified status
  is implied. Public hybrid remains on the existing bridge policy when bridging is on.

## Immutable packaged checkpoint

The shared fresh build exited 0 with **7712 tests / 159 classes / zero
failures/errors/skips**. It contains this slice and independent public regeneration
work; its whole-suite count is not an ES-only test count. Immutable artifact:
`/tmp/weknora-java-regenerate-vector-build/backend-java-0.1.0-SNAPSHOT.jar`,
SHA256 `bbb2f24feab41cf278a8751ad9a917edaf10e2102e3ce2aec4ed997388c1bde0`.

Against that same jar and frozen input root
`/tmp/weknora-java-regenerate-vector-layout`, the dedicated real-ES runner exited 0
for both `single` and `go` configurations. Each performed 14 public HTTP calls,
8 model calls and physical checks over 4 ES entries. Tasks were explicitly disabled;
these are two synchronous configuration acceptances, not worker-topology equivalence.
The runner checked shared and owner GET/POST exact Go DTOs, JWT/API scope gates,
revoked grants, actual ES deletion, no accidental default fallback, explicit unbinding
restoring default-index recall and missing owner binding error 2200.

The first single attempt exited 1 because its second Python SQLite connection had not
loaded vec0 before reading the virtual table. The runner now loads the extension on
that connection as required, preserving the same jar and assertions. Original attempt,
runner and logs remain recorded; successful reruns are not counted as extra modes.
The runner was added after the frozen build and copied independently to `/tmp`:

* Original runner SHA256: `05adada19f87a488c403d17cf09e0759d4695f5a456c292a50ea53301b1d8eb7`.
* Corrected runner SHA256: `208fdbb152fb106d42d319d095464d37e779c05b88d3b2268d446e8c4f203ef2`.
* Full attempts manifest: `/tmp/weknora-java-regenerate-vector-es-package-manifest.json`.
* Successful logs: `/tmp/weknora-java-regenerate-vector-es-package-single-second.log`
  and `/tmp/weknora-java-regenerate-vector-es-package-go.log`.

No global route/task has been marked verified by this note. Bound background
publication and the other limits above remain incomplete. Read-only next-slice audit:
`/tmp/weknora-bound-publication-next-slice-audit.md`.

The root agent subsequently completed its 38 other same-jar acceptance modes and
independently combined those with these two ES configurations: **40 final successful
process acceptances**, with original failed attempts retained. It verified the jar,
runner/log hashes, all 12034 frozen input files, and the 159-class/7712-test XML result.
Unified manifest: `/tmp/weknora-java-regenerate-vector-package-final-manifest.json`.
The dedicated temporary ES container was stopped successfully after verifying that
no package-owned indexes remained. No development database or volume was modified.
