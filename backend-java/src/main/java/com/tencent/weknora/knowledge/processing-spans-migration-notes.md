# Processing spans/stages native migration

Status: **in progress**. The HTTP reader, internal native recorder and production document/zero-enrichment postprocess integration are implemented. Fresh native shared-KB uploads have been exercised through a packaged server and its real worker, actual model HTTP, vec/FTS publication and both tracing aliases. Other enrichment handlers, native housekeeping and PostgreSQL spans still require migration/verification.

## Implemented contracts

- `GET /api/v1/knowledge/{id}/spans` and `/stages` are native aliases. Both require Viewer/retrieve and the existing document/KB ownership, shared-KB and Agent-share authorization. The bridge exact-route selector, API key gate and native role interceptor are covered by integration tests.
- Positive Go `Atoi` attempts use signed 64-bit values on this target; invalid/nonpositive values select latest. Historical/missing attempts remain explicit. Natural SQL insertion order, root/stage selection, running subtask ancestry, five canonical stages, missing-stage fallback and failure precedence follow the production Go handler.
- JSON maps preserve null/empty `omitempty` behavior; private database `id` and `error_detail` never enter the public projection. Placeholder clock values are compared literally with an injected clock captured from the Go response, without masking HTTP fields. SQL failures produce the same honest legacy fallback as Go.
- `KnowledgeProcessingSpanWriteRepository` writes the existing schema, preserves immutable identity on conflict and updates input/output/metadata only when supplied. It records JSON BLOBs on SQLite and sorts map keys/escapes HTML and U+2028/U+2029 like Go JSONMap. Every mutation includes a document owner/deleted predicate, including a second fence after initial validation.
- `KnowledgeProcessingSpanTracker` records root/stage/subspan/generation transitions, stage re-entry, same-name retry supersession, dependency cancellation, terminal-root protection, flat user cancellation and root/stage-only knowledge heartbeat. Error fields are cleaned at persistence and error byte limits follow Go. Terminal roots with missing started_at retain null. Optional enrichment-stage failures do not fail the parse root.
- Production constructors provide their own clocks; no global Clock bean is required. The reader uses the configured system zone for request-time placeholders; the writer stores instants in UTC.

## Actual Go baselines and reproducible checks

`src/test/fixtures/knowledge_processing_spans_http_fixture.go` invokes production Go handlers, middleware, services and repositories against the production SQLite migrations. It exports **130 HTTP scenes**, including real nonzero roots/stages, five real Go SpanTracker scenarios, historical/missing attempts, null/malformed JSON, ordering, last-error recovery, permissions/shares and SQL failures. The replay compares full HTTP responses and verifies that both implementations leave business tables unchanged.

`src/test/fixtures/knowledge_processing_span_writes_fixture.go` invokes the production Go KnowledgeSpanRepository. Its **9 SQL scenes** cover nil-content preservation, empty-map replacement, content replacement, escaped/nested JSON, immutable identity, clearing times, default/new attempts and NUL cleanup. Replay deliberately reverses object insertion order and compares all business tables including JSON BLOB bytes. Only declared SQLite BOOLEAN columns are translated between Go driver's bool and JDBC's integer representation; numeric node wrappers are normalized mathematically. No columns or rows are excluded.

Dedicated Spring integration tests cover aliases, API retrieve/ingest/scope, the actual native role interceptor and bridge route selector. Native tracker tests execute real SQLite writes and cover the lifecycle, failed-stage dependency graph, cancellation under done parents, heartbeat, ownership changes between validation and mutation, historical scan/re-entry, Unicode error codes and byte limits.

`KnowledgeProcessingSpanPipelineHttpIntegrationTest` adds **11 fresh production Spring pipeline cases**: shared caller tenant 7 uploads into owner tenant 8, actual model HTTP and physical vector/FTS publication, index-status and both trace aliases, denied tenant 9, empty-document terminal root, graph-only/no-extraction embedding skip, HTTP 500/429, cancellation during embedding and before delivery, durable-enqueue rollback, initial storage retry with a new attempt and retained history, lease/source successor protection, and unsupported summary with truthful completed DocReader/failed postprocess. During the model request the test checks that chunking/embedding remain running and no chunks have been published.

The combined scoped run passed **181 tests**, zero failures/errors/skips: 130 HTTP oracle, 9 SQL oracle, 13 tracker, 5 reader/auth integration, 11 native pipeline integration, 9 existing document compatibility and 4 existing upload HTTP compatibility. Log: `/tmp/weknora-java-processing-span-pipeline-test.log`. These test-method counts do not count each embedded document/upload fixture as a separate test.

From the repository root:

```sh
go run -tags sqlite_fts5 backend-java/src/test/fixtures/knowledge_processing_spans_http_fixture.go backend-java/src/test/resources/go-knowledge-processing-spans-http.json.gz
go run -tags sqlite_fts5 backend-java/src/test/fixtures/knowledge_processing_span_writes_fixture.go backend-java/src/test/resources/go-knowledge-processing-span-writes.json.gz
mvn -q -f backend-java/pom.xml -Dweknora.build.directory=/tmp/weknora-java-processing-spans-build -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib -Dtest=KnowledgeProcessingSpanGoHttpParityTest,KnowledgeProcessingSpanHttpIntegrationTest,KnowledgeProcessingSpanWritesGoParityTest,KnowledgeProcessingSpanTrackerTest test
```

To include production pipeline and the existing document/upload compatibility cases, add `KnowledgeProcessingSpanPipelineHttpIntegrationTest,DocumentProcessingCompatibilityTest,KnowledgeUploadHttpCompatibilityTest` to `-Dtest` above. The vec0 path above is this workstation's already built extension; use the local extension path on other platforms. Go fixtures use UTC explicitly. This batch does not claim a full Maven suite, PostgreSQL span parity, complete Go document-pipeline parity, arbitrary floating-point JSON BLOB parity or external Langfuse verification.

`scripts/knowledge_processing_spans_probe.py` provides a read-only packaged-service probe. Set `JAVA_SPAN_PROBE_API_KEY` or `JAVA_SPAN_PROBE_ACCESS_TOKEN`, then run:

```sh
python3 backend-java/scripts/knowledge_processing_spans_probe.py --base-url http://127.0.0.1:8081 --knowledge-id DOCUMENT_ID --require-native-records
```

The flag requires persisted spans; only a fresh native-only pipeline run can establish their provenance. The probe checks aliases, five canonical stages and private-field omission. It normalizes request-time placeholder timestamps when comparing two independent requests, after checking their validity. Persisted timestamps remain literal.

The jar package and a real packaged acceptance run passed. The temporary dedicated runner `/tmp/weknora_java_span_pipeline_packaged_smoke.py` is a read-only copy of the existing processing smoke plus assertions/probe invocations. It ran `--topology single` with Hikari pool 1, bridge disabled, a fresh database, two text uploads (own and shared), four successful real worker jobs and two actual embedding HTTP calls. Both documents produced one persisted done root and five persisted stages. Both aliases returned six persisted spans and current physical index-status counts matched actual chunks. Initial document queue payloads remained without attempt and postprocess jobs carried attempt 1. Log: `/tmp/weknora-java-processing-span-pipeline-packaged.log`; server log/database paths are in that result. This acceptance did not enable Office/DuckDB or claim that known native-library interaction was resolved.

## Production integration and remaining handoff

`KnowledgeProcessingConfiguration` now injects the tracker into both handlers. Existing constructors retain their no-tracker behavior for compatibility. `KnowledgeProcessingSpanPipeline` holds only per-delivery state. Worker fallback changes the TaskRecord's local payload copy to pass attempt to postprocess, without updating the original durable document payload. Keep task/source/attempt fencing authoritative; recording is best effort and must not replace the original business failure.

| Handler location | Recorder action | Required truthful boundary |
| --- | --- | --- |
| DocumentProcessingHandler after `repository.begin(job, snapshot)` succeeds | Resolve positive payload attempt or `open(job.tenantId(), knowledgeId, langfuseTraceId)` | Skip stale/duplicate/deleted/cancelled documents before allocating. Root allocation failures may disable recording, not processing. |
| File/parser selection and `reader.read(request)` | Begin/end/fail `docreader`, capture actual engine/type/parser counts | Cover early return failures and cancellation; sanitize public errors while storing private detail separately. |
| Source locator + DocumentChunks.create | Begin `chunking`, record `chunks_planned` | Java currently creates in memory here. Do not emit `chunks_written` or mark done until `repository.save` commits. |
| Embedding model resolution/call/validation/prepare | Begin/end/fail `embedding`, input actual model/dim/vector/keyword flags | Model generation succeeds before Java atomic publication. Mark index-write success only after commit; failure/retry keeps data/tracing honest. |
| No images / enrichment disabled | Begin+skip `multimodal` with actual reason | Do not report generated image work that never ran. |
| `repository.save` + postprocess enqueue transaction | Complete persisted chunk/index stage outputs after successful commit; include attempt in child job payload | No false done on quota/source-race/rollback/enqueue failure. Preserve job's fenced source hash/path and attempt. |
| KnowledgePostProcessHandler after supported-enrichment checks | Begin/end `postprocess`; finalize only when `repository.complete` returns true | Future fan-out dispatch and pending-subtask drain must have separate states; root completion waits for the actual finalizer. |
| Final retry exhaustion / nonretryable business failure | Fail relevant stage; mandatory pipeline failure closes root | Align actual error codes and retry semantics against Go rather than relabeling every exception. |
| Explicit user cancellation | `abort(owner, knowledge, attempt, code, message, reason)` | The flat sweep cancels open rows even beneath done dispatch stages. Connect only after the business cancellation fence succeeds. |
| Summary/question/Wiki/graph/table child handlers | Lookup owning stage and begin/end/fail generation/subspan | Reject superseded attempts before writes/counter drain; include actual model inputs/results. |

Current Java publication is atomic across chunks, index persistence and postprocess enqueue after embedding generation. Go `knowledge_process.go:630` starts chunking, calls `CreateChunks`, and records `chunks_written` at line 646 before starting embedding at line 664. Therefore failure timing and partial persistence differ. This batch does not move the Java transaction boundary or claim identical document-process side effects. The next document-pipeline parity batch must address that explicitly.

## Initial attempt allocation: current source vs stale comments

Go initial creation payloads in `knowledge_create.go:230` (file), `:428` (URL), `:670` (file URL), `:949` (passages) do **not** set Attempt and do **not** call OpenAttempt. The actual initial root is opened in `ProcessDocument` at `knowledge_process.go:3644` when payload.Attempt <= 0, after marking processing and the source/abort fences. Its preceding comment claiming initial enqueue already sets Attempt is stale. The original queue payload is not updated by this fallback, so later deliveries can allocate another attempt. This is an observed source-level retry mismatch; no end-to-end initial-upload retry experiment is claimed.

Reparse allocates before enqueue at `knowledge_process.go:2749` and passes the resulting attempt in document payloads at `:2832`, `:2886`, `:2937`. Manual enqueue (`knowledge_create.go:1131`) has no attempt; `ProcessManualUpdate` opens a fresh root at `knowledge_process.go:3466`. Avoid assuming initial/manual retries reuse an attempt based only on comments. A migration decision to improve durable attempt allocation requires an explicit Go baseline fix or a documented behavior change with a complete retry/fencing test.

## Remaining behavior

The document and zero-enrichment postprocess integration above is now active. Optional summary/question/Wiki/graph/table enrichment tracing, Go Redis/Asynq + durable Wiki backlog probe and native housekeeping stall verdicts are not complete. The reader omits `stall_state` when the probe is unavailable, matching Go with no probe; this does not verify the configured production probe path. Clock-zone combinations, PostgreSQL JSONB/span writes, worker restarts, complete queue-cancel API behavior and arbitrary model-generated JSON numbers need real infrastructure/differential coverage. Losing a lease alone does not abort a successor's attempt; explicit business cancellation closes it, while restart/housekeeping recovery remains pending. Keep the routes/module in progress until these required production paths are implemented and exercised.


### 2026-10-15 native enrichment checkpoint

Summary/question tracing and native cancel-parse are now wired and exercised in the 5,049-test full build and eight packaged flows. Question coverage includes 114 actual Go workers and 17 actual Go Post fanouts; PostgreSQL production-bean replay includes summary/question and cancellation. Existing paragraphs describe earlier checkpoints. Wiki/graph enrichment, Redis/Asynq cancellation/probe, housekeeping/restart recovery and full router parity remain incomplete. The parse root is already `done` when Go Post dispatches enrichment, so subsequent cancellation closes open children without rewriting that root.
