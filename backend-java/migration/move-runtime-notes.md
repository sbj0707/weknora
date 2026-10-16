# Native document move runtime

The public move admission and progress routes and the `knowledge:move` handler are now registered when `weknora.knowledge.processing.enabled=true`. This connects the existing transfer preflight/checkpoint/execution code to the SQL delivery queue, native SQL/ES8 index relocation, graph deletion, reparse cleanup, downstream processing, audit and profile enqueue. These entries remain **in_progress**; the complete Go migration is not achieved.

## Admission and progress

`POST /api/v1/knowledge/move` preserves Go JSON binding, validation order, ordered deduplication, same-workspace ownership, creator-or-Admin checks and both KB allow-list checks. Shared Editor access alone does not permit moving documents out of another workspace. API keys require ingest. Every document must belong to the source KB and be completed before enqueue; document and child preflight runs again in the worker before any item is claimed.

Public task IDs use the Go `kg_move` format. Delivery IDs remain internal SQL UUIDs. Task ID ownership is checked before the progress store is read; foreign-workspace requests receive not-found. Progress API keys require retrieve, with the same tenant-level progress scope as Go.

The production progress adapter uses Redis, the exact `knowledge_move_progress:` key prefix, Go-compatible JSON and a 24-hour TTL. Initial progress uses SETNX so a fast worker's progress cannot be overwritten or have its TTL extended by delayed admission. Worker progress uses SET. Progress persistence is best effort, as in Go. Connections are lazy and closed with the Spring bean.

## Delivery and effects

The move task uses the low queue, three retries and a two-hour timeout. `NamedTaskProducer` reserves a queue-scoped external business ID transactionally with the SQL job; concurrent reservations cannot leave orphan deliveries. Completed zero-retention keys can be reused; positive retention remains conflicting until the janitor removes the expired key, matching the actual Asynq sequence. Reparse child IDs are `move-reparse:<public-task-id>:<knowledge-id>` and retain completed keys for seven days. SQL job history is retained independently.

An idle janitor checks for expired completed keys without acquiring SQLite's writer. A held-business-writer probe fails against the previous implementation and passes against the updated implementation; a completion concurrent with the read is collected on the next pass. This avoids unrelated business transactions competing with a no-op retention update. Index cleanup failures now preserve their original cause internally while retaining the existing safe task error message.

Reuse preserves physical SQL index IDs, vectors and FTS postings while moving metadata to the target and clearing tags. Reparse clears the source publication, moves the binding to the target and commits the quota reduction with that checkpoint, then enqueues the retained file/manual source. A failed enqueue retries from the target checkpoint without charging cleanup twice. An already successful document is not rewritten when a batch retries another failed document. Completion of the move task in reparse mode means the rebuild has been admitted; document parsing can still be pending.

Real JWT and API-key filters are used in the native pipeline tests. Tenant keys keep the oldest real user for audit display when present, retaining the API key ID/name and its viewer/owner compatibility role; this display identity does not enlarge key authority. Synthetic system identities are omitted from the human initiator snapshot.

## Persisted tenant configuration

Admission and worker decode the original JDBC value of `storage_engine_config`. Go persists JSON bytes; converting the row to Jackson first incorrectly turned SQLite BLOB bytes into Base64 and lost the default provider. The shared decoder handles bytes and JSON text (including PostgreSQL JSON values), and rejects malformed JSON rather than silently keeping an encoded string. An actual Go admission corpus now has 52 cases, replayed as both TEXT and BLOB (104 Java cases). A native authenticated pipeline also moves a fresh upload with tenant default COS and an explicit COS target, preserving the physical indexes, resource bytes and quota. This verifies provider compatibility without calling external COS storage; the retained resource is resolved through its original catalog entry.

## SQLite timestamp interoperability

The native pipeline exposed ISO `T`/`Z` DATETIME writes from postprocessing and processing-span touches after upload. Go reads those values but rebinds them in go-sqlite3's canonical format in the transfer CAS predicate, so the claim fails. Native document publication, span touches and other document mutation/enrichment date bindings now use the UTC go-sqlite3 DATETIME representation with the original nanoseconds. HTTP/JSON timestamps remain RFC3339; PostgreSQL bindings remain typed timestamps.

The move CAS is unchanged. Existing noncanonical SQLite rows are not rewritten by admission or silently accepted: they still conflict as actual Go does. A dedicated native pipeline case preserves this legacy conflict, while fresh native uploads move without seeded or rewritten timestamps.

## Schema and configuration

Java does not apply DDL on startup. For an existing Java SQL queue, explicitly apply `backend-java/migration/sql/java-task-named-keys-upgrade.sql` to its database. Fresh test installations of `java-tasks.sql` already include the mapping table. The upgrade creates one mapping table with a cascading foreign key to the existing job UUID; it does not rename jobs or import Asynq work.

Configure Redis through `REDIS_ADDR`, `REDIS_DB`, `REDIS_USERNAME`, `REDIS_PASSWORD` and, where applicable, `REDIS_USE_TLS` / `REDIS_TLS_INSECURE_SKIP_VERIFY`. A configured TLS server-name override is explicitly unsupported. Native uploads also require the existing upload feature switch, and automatic background execution requires the tasks feature switch. Keep the bridge configuration and actual worker topology explicit.

## Evidence and limits

See [the runtime checkpoint](move-runtime-checkpoint-2026-10-16.json) for exact source/report hashes and current full-build/process results. Repeat the independent PostgreSQL worker comparison with `python3 backend-java/scripts/postgres-move-worker-parity.py`. It runs all 115 migrations, actual production Go through test overlays, then Java with one worker DB connection and eight connections for queue admission races. No production Go implementation is modified.

`KnowledgeMovePipelineHttpIntegrationTest` uses real Spring authentication, SQL workers, an owned real Redis process, controlled actual embedding HTTP, physical sqlite-vec/FTS and original resource files. It verifies eight cases covering both modes, Go-persisted tenant BLOB configuration, denied scopes, revoked JWT, cross-tenant progress, whole-batch validation, partial retry, downstream enqueue recovery and the legacy timestamp conflict. `RedisKnowledgeMoveProgressGoParityIT` separately replays exact Go JSON/byte/SETNX/TTL cases against real Redis.

Still missing or unverified: Wiki source reconciliation and target ingest; physical moves for all other index engines; complete bound-store configuration combinations; Redis TLS server-name override and all transport diagnostics; tracing-context injection and all initiator edge cases; full cancellation/lease-loss/restart/cutover behavior; full Lite synchronous execution and consumption of Go Redis/Asynq backlog. Normal bound-vector-store document writes remain rejected by the existing document resolver. Document publication transactions and general quota accounting still differ from Go outside the transfer checkpoint. These limits are not removed by entry-count coverage or passing scoped tests.

The knowledge-base move-target discovery route now runs natively; see [its scope and actual Go comparisons](move-targets-notes.md). Packaged HTTP checks cover discovery followed by reuse/reparse moves. The complete frontend UI has not been exercised, and the remaining knowledge-base APIs still require migration.

`processing_smoke.py <jar> <vec> --move --topology single|go` exercises the packaged application's actual HTTP, Redis and background workers in isolated databases and ports. The `go` name selects Java SQL worker pools; it does not switch the backend to Asynq. The move pipeline and progress IT require `redis-server` on PATH, or `-Dweknora.redis.executable=...` for Java / `WEKNORA_REDIS_EXECUTABLE=...` for the smoke script.


## Publication and validation update (2026-10-16)

A packaged move/reparse run found that new normal/manual chunks stored SQL NULL tags, whereas Go `CreateChunks` writes the empty string. Both publication repositories now explicitly insert `tag_id=''`; historical rows are not rewritten. Go document/manual snapshots now query the raw SQL tag value, and Java compares it without normalizing NULL. The extended 62-test XML reports have no failures/errors/skips; they overlap the earlier 535-test focused batch.

The current candidate jar passed the isolated `single` and `go` HTTP move runs with actual exit 0 in both. All 469 production source/pom hashes still match. The earlier r4 full build passed 9,975 tests, but it predates the tag correction and later fixture changes; current full verification has not completed. See [the latest comparison](comparison-latest.md) for exact scope and preceding failures. Neither worker topology consumes Go Asynq backlog.
