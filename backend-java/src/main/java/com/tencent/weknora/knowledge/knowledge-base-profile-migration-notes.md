# Native knowledge-base description migration

Scope: opt-in `kb:profile` worker, its document/Post/Summary triggers, disabled-to-enabled KB settings transition, and synchronous `POST /api/v1/knowledge-bases/:id/profile/generate`. The global migration remains incomplete; none of these results declare the whole KB module or route verified.

## Production behavior

The task runs in `summary`, with three total attempts and a five-minute timeout. Automatic refresh is scheduled after 30 seconds and deduplicated by KB ID plus the 30-second time bucket. Forced refresh gets a fresh immediate job. This task owns no document enhancement counter or parse span. Document completion and summary completion enqueue best effort; configuration update enqueues only after the business transaction commits.

Aggregation reads enabled, completed/finalizing, nondeleted documents in the KB owner's tenant. It preserves Go's stable statistics, UTF-8 string ordering, bounded samples, 500-ID tag lookup, fingerprint, cache rules and failure retention. Empty KBs clear the generated description without calling a model. Automatic refresh requires profile_config.enabled; forced generation still requires a permanent document KB. Chat uses the configured profile model or summary-model fallback, temperature 0.2, max_tokens 800 and thinking=false. Generated content is separate from the handwritten description.

The synchronous request forces refresh, has a two-minute deadline, and creates no queue job. It uses the same generation implementation as the worker. Authorization retains the original caller, resolves the KB owner, enforces creator/admin, shared Editor, manage_kbs and the API-key KB allow-list. Scoped shared keys retain Go's Viewer restriction. Deployment WEKNORA_LANGUAGE takes precedence over the first Accept-Language tag, with zh-CN fallback.

`GoKnowledgePrompts` resolves the current Go configuration's generate_kb_description_prompt_id and YAML prompt collection. Java can explicitly select the Go configuration with `weknora.go.config.file` or override the content with `weknora.knowledge.profile.prompt`. Without a selected template the service uses Go's inline fallback. Other generation components still need their own complete configuration migration.

No profile DDL is created by a constructor. The existing Go profile migration supplies the columns. Existing Java queue SQL is still an explicitly applied operator migration.

## Reproducible evidence

- 52 actual production Go service/SQL/model-HTTP cases: populated/empty KBs, eligibility, malformed typed payload, models, cache/force, 530 documents, sampling/normalization limits, owner/delete/name changes during Chat, typed model JSON fallback/case-fold/duplicate/null and real loaded Go configuration versus custom content.
- 23 actual Go public handler + API-key/ownership/KB-access/language middleware cases compared to native MVC, complete response bodies, SQL and model requests. Generated timestamps are checked as timestamps and normalized for comparison.
- 12 actual Go producer/Asynq cases plus one native next-window/owner check: queue, retry/timeout, delay, dedup, force, tracing and locale.
- 10 production Spring/Hikari1 cases: shared multipart upload -> Doc -> Post -> Profile, Summary trigger, settings transition, real model HTTP, cache and force, ownership/lease/cancellation guards, synchronous shared access and actual AuthFilter API-key capability/allow-list/shared-viewer enforcement.
- 2 selected-prompt checks, including comparison against the production Go LoadConfig output.
- Total focused JUnit: **100 / 5 classes / 0 failures / 0 errors / 0 skipped**, exit 0; `/tmp/weknora-java-kb-profile-final-test.log`.
- Final PostgreSQL: all 115 Go migrations, pool1, bridge=false, 10 uploads/35 jobs/33 success/2 cancellation/20 Embedding HTTP/17 Chat HTTP. Automatic profile after settings commit, owner/shared synchronous generation, JSONB statistics and zero counters. `/tmp/weknora-java-postgres-profile-graph-final.log`, artifact `weknora-postgres-processing-523htxze`, actual exit0.
- Included in immutable clean verify **5,757 / 121 classes / zero failures/errors/skipped**, actual exit0, `/tmp/weknora-java-profile-graph-verified-build/backend-java-0.1.0-SNAPSHOT.jar`. Same jar passed 14 packaged checks. Its two profile-combination runs each verified 2 uploads, 11 jobs, 6 embedding and 9 Chat calls, including delayed generation plus forced own/shared synchronous generation; `/tmp/weknora-java-profile-package-kb-profile-{single,go}-retest.log`, both exit0. The initial runner asserted available_at after completion instead of before claim; pending scheduling and first attempt timing are now checked without changing production queue behavior.

Reproduce actual Go oracles from the repository root:

```sh
go run backend-java/src/test/fixtures/knowledge_base_profile_fixture.go /tmp/kb-profile.json
go run backend-java/src/test/fixtures/knowledge_base_profile_fixture.go /tmp/kb-profile-http.json --http
go run backend-java/src/test/fixtures/knowledge_base_profile_enqueue_fixture.go /tmp/kb-profile-enqueue.json
```

## Corrected Go baseline and remaining boundaries

The original Go generated-profile update matched only KB ID. An actual mid-model ownership change showed the old tenant writing into the new tenant's KB. Go now updates by the authorized execution tenant, KB ID and nondeleted state, and rejects zero affected rows. The service establishes execution scope from the already authorized KB. The pre-fix oracle is retained at `/tmp/weknora-go-kb-profile-before-fences.json`; the current Go/native tests compare the corrected production behavior.

Native queued writes additionally check the SQL task's current attempt, lease owner/expiry and cancellation, before and after the transaction. Go/Asynq has no matching SQL lease row. Failure messages in service SQL comparisons are required to be nonempty but normalized across driver/provider implementations; public HTTP messages in the 23 cases are compared exactly. This does not establish equivalence for every possible provider diagnostic.

Go's current fingerprint omits file type, folder and created_at even though these affect statistics. This known cache boundary is preserved, not declared fixed. Corrupt cross-tenant tag relations, all date-offset ordering cases and all malformed/duplicate task JSON still need expanded differential coverage. The native tag join is owner/KB scoped. SQL job dedup retention and Go's Redis task retention differ. Backlog consumption, full synchronous task mode, all remaining public KB operations, restart/upgrade races, Docker/Linux and production traffic cutover remain pending. Bridge mode continues to send this new public route to Go until its ownership policy is explicitly changed.
