# Native question generation migration

This batch implements the existing `question:generation` task. There is no separate Go `question:batch` task: whole-document legacy, single-chunk and windowed batch deliveries share the same handler. Production document processing and postprocessing now dispatch native summary and question tasks together when their effective document configuration enables them. Graph, Wiki, auto-tag and KB-profile refresh remain separately guarded migration gaps.

## Behavior and storage

The postprocess plan selects extractable plain-text chunks, including disabled chunks, sorts them by source position and splits them into windows of 20. Image-only placeholders are excluded using Go's balanced markdown-image rules; each window carries its actual preceding/following boundary IDs. Question count defaults to 3 and is capped at 10. Tasks use the `question` queue, three retries and a 30-minute timeout; ordinal zero and absent boundary fields retain Go's JSON omission semantics.

Question generation uses the effective summary model and embedding model, existing native model clients and index-writer resolution. Prompts preserve title, custom document/KB instructions, locale, boundary context and parent-specific image evidence. Metadata is committed before embedding/index creation, so a failed embedding delivery retains generated-question metadata exactly as the Go worker does. Generated question sources are actual relational, vector and keyword entries pointing to the source text chunk, with Go's SHA-256 source-ID shortening rule. Repeated deliveries retain the existing Go alias behavior rather than silently deleting old question indexes.

Tenant ownership, document/KB/chunk binding, current revision, processing attempt and active task lease are checked before publication. Missing/superseded chunks are skipped; a superseded attempt never drains its successor's counter. Per-chunk provider and metadata failures retain Go's swallow-and-continue behavior; embedding/index failures retry the task and only terminal delivery releases its slot. Business document cancellation can acknowledge and release the old slot, while explicit task cancellation, interruption and lost lease cannot be acknowledged as success. The new receipt makes each task's terminal decrement idempotent and permits summary and questions to share the same pending counter without double draining. The root attempt closes when postprocess dispatch completes, as in Go; later question failures affect their own child spans.

Repositories do not execute schema DDL in constructors. Fresh databases explicitly apply `migration/sql/java-tasks.sql`. Existing Java task databases explicitly apply `migration/sql/java-question-drains-upgrade.sql` against their configured SQLite or PostgreSQL database before enabling processing. The table contains only task ID, tenant ID, knowledge ID and attempt. The upgrade is idempotent; schema tests verify both repeated application and receipt preservation.

## Actual Go comparison

`src/test/fixtures/question_generation_fixture.go` executes production `ProcessQuestionGeneration` through a real Asynq server/client with isolated miniredis, actual retry context, real processing-span tracking, local chat/embedding HTTP and SQLite/vec index storage. `src/test/resources/go-question-generation.json` contains 114 worker cases: 38 scenarios for each of the three payload modes. The comparison checks actual HTTP requests, SQL metadata/index content, revisions, document states/counters and span input/output/status/error code. Cases include default/custom prompts, nonzero data, existing metadata, current revision 4, boundaries/images, missing models/KB/chunks, foreign tenant/document/neighbors, provider failures, intermediate/final retry, cancellation during Chat and supersession during Chat.

`src/test/fixtures/question_generation_fanout_fixture.go` invokes the production Go postprocess handler and captures 17 real task/SQL/span projections, including mixed summary/questions, zero/default/capped counts, 41-chunk windows, image/OCR-only input, disabled chunks and embedding strategy, no enhancement and invalid document/KB binding. Queue, retry, timeout, payload omissions, boundary IDs, task order and structural group spans are compared. Both fixture programs are run from the repository root with `go run` and their exact JSON output is copied to the corresponding resources.

The initial Go baseline exposed three safety bugs: a payload could select a chunk/neighbor from another document; cancellation during Chat could still publish questions; and a same-document wrong-KB postprocess row returned SkipRetry with its span/root left running. The Go baseline was fixed in the question branches and the postprocess rejection guard before regeneration. The original unsafe oracle remains at `/tmp/weknora-go-question-generation-before-security-fix.json`; no unsafe scenario was removed to manufacture parity. The final worker fixture also verifies that a new attempt opened during Chat blocks old publication and counter draining.

The dedicated production Spring tests cover fresh shared-KB multipart upload through Doc/Post/Question, actual vector/FTS and relational records, owner-side spans/stages, completed public index status and chunk details, foreign access rejection, simultaneous summary/question counter ownership, real window boundaries, enqueue rollback, lease loss, supersession, intermediate/final index failure, business cancellation and invalid payload ownership. Tests store typed KB JSON using Go's SQLite BLOB Scanner contract, so public index configuration and actual writes agree. Schema tests verify explicit setup without constructor DDL. Existing summary and tracing pipeline tests are rerun together to detect production-wiring regressions.

## Verification and limits

Focused commands:

```sh
go test ./internal/application/service -run '^TestQuestion(ChunkBinding|FanoutRejects)' -count=1
mvn -q -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-question-generation-build \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib \
  -Dtest=QuestionGenerationGoParityTest,QuestionGenerationFanoutGoParityTest,QuestionGenerationPipelineHttpIntegrationTest,QuestionGenerationSchemaTest,SummaryGenerationPipelineHttpIntegrationTest,KnowledgeProcessingSpanPipelineHttpIntegrationTest test
```

The final focused run passed 159 tests with zero failures, errors or skips: 114 worker, 17 fanout, 9 production Spring pipeline, 2 schema, 6 existing summary pipeline and 11 existing processing-trace pipeline tests. It is recorded in `/tmp/weknora-java-question-final-test.log`; Go guard tests also passed and are recorded in `/tmp/weknora-go-question-security-final-test.log`. The dedicated comparison scope is real SQLite/vec/FTS and provider HTTP. Separate root-owned PostgreSQL and packaged-process acceptance runs are reported by their own validation artifacts, not established by these fixture tests.

Explicit remaining differences and limits:

- Native summary/question counter handoff and durable enqueue are atomic. Go seeds the counter and performs separate queue writes, then releases shortfalls. Native rollback is tested, but this is not identical partial-enqueue behavior.
- Generated question IDs are opaque native UUID-derived IDs rather than Go nanosecond IDs. Fixtures normalize only newly generated IDs while retaining source-ID hashing, question content, index counts and revision semantics.
- Go batch embedding groups and map iteration order vary. The comparison preserves chat-message order, embedding input multiset, model/options and retry multiplicity, but normalizes embedding request partition/order. It does not prove wire-identical batching.
- Native publication retains lease/attempt/revision fences at its index transaction; the Go baseline does not offer equivalent atomic cross-store fencing. External stores cannot participate in the native SQL transaction.
- The public synchronous chunk-question regeneration API is not implemented by this task batch. Neo4j graph storage, Langfuse external trace events and model-usage accounting are not established here.
- Pathological prompt replacement values containing additional placeholders, deployment timezone differences, arbitrary malformed stored JSON and all external index drivers are outside this oracle. This batch does not establish complete Go parity or authorize a production cutover.
