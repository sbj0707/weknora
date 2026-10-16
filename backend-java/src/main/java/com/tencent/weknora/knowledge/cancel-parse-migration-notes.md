# Native document parse cancellation

`POST /api/v1/knowledge/{id}/cancel-parse` is available when native processing is
explicitly enabled and the Go bridge is disabled. Hybrid ownership remains with
Go: the native SQL queue cannot dequeue or cancel Redis/Asynq jobs.

The controller resolves the document's authoritative KB, applies the existing
creator/admin ownership and shared editor write grant, and verifies the document
owner. API keys require `ingest` before child lookup and the parent KB allow-list.
A scoped API key retains Go's shared-viewer permission cap; an allowed key can
cancel documents in its own tenant. Human and API-key audit attribution follows
the original caller rather than replacing it with the shared KB owner.

The state transition sets `parse_status=cancelled`, error `用户已取消解析`, and
`pending_subtasks_count=0` together. Pending/processing summary status becomes
`none`; completed/failed summary state remains. Published chunks, descriptions,
profiles and indexes remain intact. Completed/failed parse statuses return Go's
400/1000 error, while deleting has its own 400/1000 message. Already-cancelled
requests still dequeue newly enqueued work but do not update state, close spans,
scrub Wiki again or add another audit event.

Cancellation aborts the latest attempt's open spans. A parse root already closed
by Go-style postprocess dispatch remains `done`; open enrichment children stop.
The native queue mirrors the Go inspector's seven per-document task types:
`document:process`, `manual:process`, `image:multimodal`, `knowledge:post_process`,
`question:generation`, `summary:generation`, `chunk:extract`. Table/KB/delete jobs
are excluded. Pending/retry jobs become canceled; running jobs receive a cancel
request. All queue reads and writes are owner-scoped, with keyset pages of 500;
invalid JSON and foreign/other-document payloads are ignored. Pending Wiki ingest
is scrubbed while retract operations remain. Queue/Wiki/audit failures are best
effort after the business cancellation commits, as in Go.

Native writes add a transaction and terminal-status conditional update so a
concurrent promotion cannot be overwritten. Wiki cleanup also requires the
resolved owner tenant. Those fences are stronger than Go's unconditional column
update and unscoped dedup-key deletion; their race behavior is not claimed
identical. Native task states/leases remain SQL contracts, not Asynq bytes. Raw
SQL error strings are not an HTTP compatibility guarantee.

Actual Go service fixture generator:

```sh
go run backend-java/src/test/fixtures/knowledge_parse_cancel_fixture.go \
  > backend-java/src/test/resources/go-knowledge-parse-cancel.json \
  2> /tmp/weknora-go-cancel-parse.log
mvn -q -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-cancel-parse-build \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib \
  -Dtest=KnowledgeParseCancelGoParityTest,KnowledgeParseCancelHttpIntegrationTest test
```

54 actual Go cases strictly compare SQL projections for nine parse statuses,
five summary statuses, idempotency, missing/foreign/deleted knowledge, missing
tracker/Wiki/audit tables, and failed state update. They execute the real Go
repositories, tracker, audit and pending-op repository. Eight Spring MVC cases
use production beans and Hikari pool 1, including shared writes, denied access,
API-key scopes, active cancellation during actual embedding HTTP, preservation
of previously published indexes, idempotency and more than one page of tasks.
The suite passed 62 tests with zero failures/errors/skips, log
`/tmp/weknora-java-cancel-parse-final-test.log`. This is Go service SQL differential
plus native HTTP integration, not a full Go router/middleware differential.

Fresh disposable PostgreSQL/ParadeDB acceptance also passed after all 115 Go
migrations: six successful uploads (plain/summary/summary+questions, own/shared),
two more uploads cancelled through HTTP during real summary model I/O,
24 jobs (22 succeeded, two canceled), 14 embedding and eight Chat calls.
It checks retained halfvec, JSONB profiles/question metadata, parent counters,
Wiki ingest/retract cleanup, audit attribution and closed traces. Log
`/tmp/weknora-java-postgres-enrichment-final.log`. Full packaged/native-worker
acceptance remains a separate check; this does not verify Redis cancellation,
queue restart/cutover or all formats and storage backends.
