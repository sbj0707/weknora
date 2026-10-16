# Native document index status and chunk details

Two read-only routes now execute Java business logic:

| Route | Result |
|---|---|
| `GET /api/v1/knowledge/{id}/index-status` | Five index kinds, owner-scoped persisted counts, latest attempt/retry evidence, last build time, model label and bounded Wiki links |
| `GET /api/v1/knowledge/{id}/chunks/{chunk_id}/details` | Complete chunk DTO, immutable parser content/context header, children, actual index texts/vectors, generated questions and Wiki citations |

Both return the existing frontend `{"success":true,"data":...}` contract. `KnowledgeIndexReadController` requires Viewer and API-key `retrieve`, then reuses `KnowledgeContentReadAuthorization` / `KnowledgeBaseReadAuthorization` for direct/shared/Agent KB grants and API-key KB allow-lists. A document owner mismatch is rejected; chunk reads additionally require the authorized tenant, KB, document and chunk ID simultaneously, excluding soft deletes. Shared reads use the KB owner's tenant engine configuration and model visibility. The original caller is never promoted to an owner or write principal. Exact route patterns are registered in `AuthFilter`, `NativeRoleGuard` and `GoCompatibilityBridge`.

Storage inspection is read-only: it never calls index `initialize()`, repairs FTS tables, creates dimensions, contacts embedding models or reindexes chunks. SQLite keyword rows require actual FTS row IDs; vectors require actual vec0 row IDs and correctly sized float32 blobs. PostgreSQL reads the existing `embeddings` metadata and `halfvec` text; keyword counts retain Go's metadata-row semantics. Question aliases are counted separately from primary chunks and require every configured retrieval kind/store. Replicated store counts use minima, never summed duplicates. Unsupported stores make that retrieval kind unavailable; a foreign or external connection binding does not fall back to the application's main database.

SQLite vector inspection requires the operator-selected existing absolute `WEKNORA_SQLITE_VEC_EXTENSION` / `weknora.sqlite.vec.extension` library at v0.1.6. Keywords and other available evidence remain usable when vector inspection is unavailable. Different processors/readers now verify and reuse a matching vec0 registration on the same pooled physical connection, avoiding duplicate native extension initialization. Only the configured library is loaded; model/document metadata cannot select a library.

Wiki inspection uses owner+KB scope and exact JSON-array references: a page explicitly citing the chunk is `scope=chunk`; a document summary/legacy page without chunk references is `scope=document`. Pages citing only other chunks are excluded from that chunk's details. Document previews read slugs/titles, cap returned links at 50, and retain the full count. No wildcard interpretation of document IDs occurs. Production Go's SQLite Wiki source-reference queries were repaired alongside the alignment audit: both runtimes now use exact/prefix JSON-array matching, Unicode-safe prefix lengths and execution-owner tenant predicates. Foreign-tenant pages with a matching KB ID are excluded from both overview and detail.

`KnowledgeIndexStates` ports production Go evidence logic: latest retry per span name, question fan-out child completion, enqueue gaps, cancellation, partial failures, skipped stages, configuration changes, missing records and content revisions. Parse completion alone is insufficient. Missing history/statistics remain `unknown` with null counts rather than successful zeroes. The native Java document/postprocess/summary pipeline now publishes processing spans; fresh native uploads have been exercised over SQLite and PostgreSQL. Historical documents with no recorded attempt still honestly show `unknown/history_missing`. Pipeline publication and failure checkpoints differ from Go and remain documented in processing-spans-migration-notes.md. Parent chunks are context-only and never reported as primary vector records. Children retain the production repository return order. The query uses owner+parent+soft-delete scope, then the service validates every child's KB/document/parent and rejects an inconsistent related row rather than silently filtering it. Status reads latest-attempt spans before reloading the KB; detail reloads the KB after its chunk lookup. Model labels use insertion order for the latest embedding input while completion uses latest updated time per span name, matching Go retries.

The inspection-only repository preserves production SQL Scanner semantics: SQLite BLOB configurations/reference arrays are decoded, while TEXT values retain Go's default/ignored behavior. Raw chunk metadata reaches the Go-compatible typed question decoder before the generic DTO projection; duplicate fields, null string fields, case folding, invalid types, nullable question revision and legacy revision fallback therefore retain their original meaning. Span model IDs require strings; counters require numbers. No generic string/number coercion supplies build evidence.

A request-wide budget starts after access resolution, matching Go: 8 seconds for status, 12 seconds for details. All inspection reads share the remaining budget; SQLite progress handlers interrupt CPU-bound SQL and are cleared before the connection returns to its pool. JDBC query timeouts use the remaining time. Optional failed reads still return honest unavailable rows; a failed mandatory chunk/KB read retains its endpoint error contract. Client-disconnect propagation remains unimplemented.

## Validation and remaining scope

The corpus in `src/test/resources/go-knowledge-index-states.json.gz` contains **964 production Go state cases**, generated by the opt-in Go test (not copied Java algorithms):

```sh
WEKNORA_JAVA_INDEX_STATE_FIXTURE="$PWD/backend-java/src/test/resources/go-knowledge-index-states.json.gz" \
  go test ./internal/application/service -run TestExportJavaIndexStateFixtures -count=1 -v
```

`KnowledgeIndexStatesGoParityTest` replays the complete corpus. A second corpus, `src/test/resources/go-knowledge-index-http.json.gz`, contains **160 production Go HTTP scenes**, using actual handler/service/repository code, SQLite migrations and vec0/FTS. It snapshots SQL types (including BLOBs), all business table data and Go read side effects, then replays the same seed through Java MVC with an identical pre-authenticated caller. Responses compare every field, omitted/null field and array order, with numerical value normalization only; there are no excluded fields or masked Wiki differences. Java business table data must remain unchanged, and Go before/after snapshots must match. Cases include nonzero/empty data, disabled and historical-missing indexes, retries/models, typed/duplicate metadata, current/stale/legacy questions, foreign/deleted children, storage failures, API-key scopes, shared/Agent KB access, Unicode Wiki IDs and the 50-link overview cap. The separate full Spring integration methods exercise actual JWT/API-key authentication.

```sh
go run -tags sqlite_fts5 backend-java/src/test/fixtures/knowledge_index_http_fixture.go \
  backend-java/src/test/resources/go-knowledge-index-http.json.gz
```

`KnowledgeIndexReadDeadlineTest` executes a real long recursive SQLite query to verify deadline interruption, connection reuse and nested scope cleanup. Real Spring MVC/Go SQLite-schema tests execute the native routes against real vec0/FTS, including shared owner storage, API-key capabilities/allow-list, missing physical rows, missing statistics/history, typed question metadata, content revisions, soft deletes, unrelated chunks and unavailable/foreign bindings. `SQLiteIndexGoParityTest` retains the original 704 production Go vec0/FTS queries and additionally verifies independent readers sharing one physical registration.

Run the targeted checks with an isolated build directory:

```sh
mvn -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-document-index-regression-build \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib \
  -Dtest=KnowledgeIndexStatesGoParityTest,KnowledgeIndexReadHttpIntegrationTest,KnowledgeIndexReadGoHttpParityTest,KnowledgeIndexReadDeadlineTest \
  clean test
```

The subsequent alignment audit passed **1,137 tests across 4 classes** (965 state/alias cases, 160 complete Go HTTP scenes, 10 full native MVC methods, 2 deadline methods), with zero failures/errors/skips. Log: `/tmp/weknora-java-index-audit.log`; reports: `/tmp/weknora-java-index-audit-build/surefire-reports`. The earlier affected-component run passed 2,248 tests across 11 classes before this deeper audit. These are targeted regression runs, not a full Maven suite or packaged-process infrastructure validation.

These routes remain **in_progress**. Neo4j graph readers, external vector engines and non-default bound connections are not migrated, so their graph/statistics/details are honestly unavailable. PostgreSQL halfvec reads have code but lack real PostgreSQL/ParadeDB validation in this batch. The 160-scene SQLite oracle does not establish all arbitrary malformed JSON/time/ID/driver behavior. Client-disconnect cancellation, production Go/Java deadline-error differential tests, Neo4j graph parity, true external-store parity and a packaged-process rollout remain pending. PostgreSQL halfvec reads remain code-only without real infrastructure verification. No frontend code, Go implementation, production database, running service or production routing configuration is replaced by these changes.

With the Go bridge enabled, the existing `JAVA_NATIVE_ROUTES_ENABLED=true` selects these two GET routes along with the earlier native routes. With that switch false, they continue through Go. Enabling them does not remove the need for Go workers and the remaining unmigrated routes.
