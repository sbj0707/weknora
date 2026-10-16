# Public hybrid search: native SQLite and PostgreSQL slice

GET and POST `/api/v1/knowledge-bases/:id/hybrid-search` have native controller, authorization, request decoding, recall, hydration, optional rerank and resource URL handling. The endpoint uses the actual `SQLiteIndexRepository` or `PostgresIndexRepository`, the persisted owner model and original-caller KB grants. It does not invoke the Go business service. Status remains **in progress** for replacement of the complete Go endpoint: bound vector stores and external engines return an explicit 503/code 2201 rather than querying a different index. `GoCompatibilityBridge` has deliberately not added this endpoint to its native whitelist; native HTTP verification uses `JAVA_GO_BRIDGE_ENABLED=false`.

## Actual differential evidence

The final fixture runs the production Go Gin handler, API-key capability middleware, KB/shared/agent authorization, knowledge-base service, real repositories, loopback model HTTP and physical indexes. It is not a hand-written expected response. `hybrid_search_fixture.go` exports 143 cases. Java compares the entire HTTP status/body and provider request bodies/order against those cases. Numeric JSON nodes use exact decimal value equivalence (e.g. `1` and `1.0`); no epsilon, array sorting or embedded JSON-string rewriting is applied.

Coverage includes GET JSON body and query-only rejection, empty/null/duplicate/wrong-type bodies, int64/float32 limits, rerank defaults/errors/models/thresholds, KB/document/tag filtering, own/shared/agent/API-key grants, revoked membership, secondary-KB allowlists, multiple owner groups, disabled/deleted/processing chunks, exact chunk/document/KB owner binding, parents/relations/neighbors/image context, FAQ typed negative metadata, iterative recall and the 500-candidate limit, configured vector-only/keyword-only engines, Go Unicode simple lowercase, model dimension errors, public URL scope rejection and typed duplicate rerank metadata. Bound/cloud stores are intentionally absent from the supported engine set.

Final focused command (Java 21):

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -Dweknora.build.directory=/tmp/weknora-java-hybrid-final-build \
  -Dtest=HybridSearchGoHttpParityTest,HybridSearchCompositeGoParityTest,HybridSearchPipelineHttpIntegrationTest,HybridSearchDeadlineTest,HybridFoundationGoParityTest,RerankMetadataGoParityTest \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-retrieval-native/vec0.dylib \
  -Dweknora.jieba.library=/tmp/weknora-jieba-formal-rebuilt/libweknora_jieba.dylib \
  -Dweknora.jieba.dict=/tmp/weknora-jieba-formal-rebuilt/dict clean test
```

Actual exit 0, six classes/442 JUnit tests, zero failures/errors/skips: 143 HTTP cases, 288 raw float64-bit cases, six production Spring/AuthFilter/Hikari1 cases, two real in-flight SQLite SQL deadline/cancellation cases, plus two existing foundation methods (428 algorithm and 12 wire scenarios) and one metadata method (80 scenarios). The old foundation algorithm method has its existing tolerance; it is not presented as raw-bit proof. Log: `/tmp/weknora-java-hybrid-final143.log`; XML: `/tmp/weknora-java-hybrid-final-build/surefire-reports`.

Independent owned PostgreSQL runner:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  python3 backend-java/scripts/postgres-hybrid-search-parity.py \
  --jieba-library /tmp/weknora-jieba-formal-rebuilt/libweknora_jieba.dylib \
  --jieba-dict /tmp/weknora-jieba-formal-rebuilt/dict
```

Actual exit 0: all 115 Go migrations, 143 separately generated actual-Go PostgreSQL cases, physical halfvec/ParadeDB, native Java/Hikari1. Owned database name is validated; the script creates/stops only its own container. Log `/tmp/weknora-java-postgres-hybrid-final143.log`, artifacts `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-hybrid-o9rj1b2g`.

Affected Go service/handler/reranking tests and `go test -tags sqlite_fts5 ./...` both exited 0 after the final production changes; logs `/tmp/weknora-go-hybrid-fma-tie-affected.log` and `/tmp/weknora-go-full-public-hybrid-final.log`.

## Go baseline repairs are explicit

The original actual 98-case HTTP fixture is preserved at `/tmp/weknora-go-hybrid-search-before.json`. It exposed an indexed chunk from the wrong persisted KB. Go and Java now enforce the same persisted owner/KB/document tuple for primary hits and every expanded context/image node; ordinary own/shared/agent access remains covered by actual production HTTP cases.

Go map iteration caused expanded context order to differ in five of 20 identical requests (`/tmp/weknora-go-hybrid-search-context-unordered.json`). Go extras and Java extras now use UTF-8 chunk-ID order; primary ranked order is preserved. Ten normal cross-owner searches also returned different first chunks for identical scores (`/tmp/weknora-go-hybrid-search-multiowner-unordered.json`). Go `sortByScoreDesc` and Java `HybridFusion` now use the same UTF-8 chunk-ID tie-breaker. The affected foundation/wire fixtures were re-exported from production Go, not normalized in Java. Previous fixtures remain under `/tmp/weknora-hybrid-before-ties`.

Go ARM64 previously fused composite multiply/add implicitly and differed from the Java expression by one ULP. Go `CompositeScore` now explicitly specifies `math.FMA(.1, sourceWeight, math.FMA(.3, baseScore, .6*modelScore))`, and Java uses the exact two `Math.fma` operations. The 288 production Go oracle outputs compare raw float64 bits. Oracle execution is recorded on ARM64; no x86 execution is claimed. The explicit arithmetic specifies the same rounding on supported architectures. Old implicit baseline: `/tmp/weknora-go-hybrid-composite-before-explicit-fma.json`.

## Operational scope and remaining work

SQLite requires `WEKNORA_SQLITE_VEC_EXTENSION`. Chinese MMR requires the native Jieba library and dictionary configured with `weknora.jieba.library`/`WEKNORA_JIEBA_LIBRARY` and `weknora.jieba.dict`/`WEKNORA_JIEBA_DICT`; native Chinese tokenization is not approximated. Basic disabled-processing/disabled-Neo4j startup and production public beans are exercised by the Spring tests.

Go's 12-second index resolution budget is retained. Multiple owner/store groups get the configured per-group retrieval timeout (default 30 seconds); a single group has only its caller deadline. The newer development batch below implements four concurrent owner/store groups, sibling cancellation and real PostgreSQL blocked-store deadlines. These changes are not part of the historical 6371-test JAR. JDBC cancellation and SQLite progress handlers stop actual work and release pool connections. Servlet client-disconnect propagation is not claimed: the upstream deadline/cancellation tests inject the caller context at the controller boundary.

Resource rewrites use Go-compatible RE2 word boundaries, request-local memoization and the actual storage resolver; unavailable providers retain references. The full cloud/bound-engine contract and public session/chat/SSE pipeline remain separate migration work.

`backend-java/scripts/hybrid-search-smoke.py` is the independent packaged HTTP runner: isolated SQLite vec/FTS seeded from an actual Go fixture, production JWT/API authentication, own/shared GET+POST, capability/allowlist/public-URL gates, revoked share, exact Go DTO and loopback model HTTP. Final immutable-JAR single/go runs both actually exited 0: each made 11 public HTTP requests, five real loopback embedding calls, and verified four physical index entries. Logs `/tmp/weknora-java-public-search-delete-hybrid-package-single.log` and `/tmp/weknora-java-public-search-delete-hybrid-package-go.log`. The exact root full-checkpoint JAR is `/tmp/weknora-java-public-search-delete-verified-build/backend-java-0.1.0-SNAPSHOT.jar`, 152634621 bytes, SHA256 `0c4f3f8a84ee6a7b014898ef54a6c3d98597a3f8260f00797de23566095fd00a`; full clean verify exited 0 with 132 classes/6371 tests/zero failures/errors/skips. The runner disables task execution and processing because this acceptance target is the public synchronous search endpoint; it validates both configured topology values without claiming worker fanout proof. The distinct graph packaged runner tests actual background task fanout. This checkpoint retains the explicitly noted concurrent-owner scheduling difference and the unsupported bound/external stores.


## Concurrent owner/store development batch

The native recall path now runs at most four groups concurrently, matching Go's actual fanout cap. A failed group cancels its siblings and returns the whole-operation error, without publishing partial results. Multi-group deadline/failure returns Go's HTTP 400/code 2201; explicit caller cancellation returns HTTP 500/code 1007. The direct single-group path preserves Go's HTTP 500/code 1007 `timeout: context deadline exceeded` or `context canceled` response. A SQL cancellation timer enforces deadlines below one second rather than relying only on JDBC's whole-second query timeout. JDBC pool acquisition also checks the caller budget; abandoned late borrows are returned to the pool.

The actual Go fixture `backend-java/src/test/fixtures/hybrid_search_blocked_fixture.go` runs the production handler/service/physical PostgreSQL engine under `LOCK TABLE embeddings IN ACCESS EXCLUSIVE MODE`. Its six exported scenarios cover multi-group store timeout, caller deadline, caller cancellation, six-owner/four-running-store cap, and the single-group caller deadline/cancellation. The resource `go-hybrid-blocked.json` is copied from this execution, not constructed or normalized. No Go production source changed in this scheduling batch.

The owned runner `backend-java/scripts/postgres-hybrid-search-blocked-parity.py` applies all 115 Go migrations and runs the six strict native HTTP/model/physical-store cases independently with Hikari pool sizes 4, 2 and 1: 18 native cases, all zero failures/errors/skips, actual exit 0. The tests observe real simultaneously blocked statements, enforce a 300 ms parent budget, and verify that cancellation leaves the same physical PostgreSQL backend PIDs reusable. The physical pool is warmed before locking so the four-store cap assertion measures application scheduling rather than connection-startup latency. Logs and artifacts:

- `/tmp/weknora-java-postgres-hybrid-blocked-final6.log`
- `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-hybrid-u7xl_v3r/build-pool{4,2,1}/surefire-reports`

The first single-group real run exposed a native 503 response and roughly 1044 ms wait against Go's 500 response and roughly 302 ms wait. The fix checks the caller context in the direct fast path and cancels actual SQL at the exact deadline; the final native runs complete around 330 ms. The original failure remains at `/tmp/weknora-java-postgres-hybrid-blocked-single-first.log` and owned artifacts `weknora-postgres-hybrid-aalg1rix`. Earlier cold-pool cap failures remain in `/tmp/weknora-java-postgres-hybrid-blocked-cap-{first,second}.log`; they are not silently dropped or response-normalized.

Final focused Java command, from the repository root:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-hybrid-concurrent-current-build \
  -Dtest=HybridSearchGoHttpParityTest,HybridSearchFanoutTest,HybridSearchDeadlineTest,HybridSearchCompositeGoParityTest,HybridSearchPipelineHttpIntegrationTest,HybridFoundationGoParityTest,RerankMetadataGoParityTest,KnowledgeBaseReadAuthorizationCompatibilityTest \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-retrieval-native/vec0.dylib \
  -Dweknora.jieba.library=/tmp/weknora-jieba-formal-rebuilt/libweknora_jieba.dylib \
  -Dweknora.jieba.dict=/tmp/weknora-jieba-formal-rebuilt/dict clean test
```

Actual exit 0 and independently read XML: eight classes/509 JUnit tests, all zero failures/errors/skips. This includes the original 143 HTTP cases, 288 raw-bit FMA cases, six production Spring cases, three fanout tests, three direct SQL/acquisition deadline tests, the foundation/metadata methods and all 63 shared read-authorization cases. Log `/tmp/weknora-java-hybrid-concurrent-current.log`. The final ordinary PostgreSQL 143-case/Hikari1 regression also actually exited 0: `/tmp/weknora-java-postgres-hybrid-concurrent-current143.log`, artifacts `weknora-postgres-hybrid-nxy72p03`. The foundation's existing tolerance remains distinct from the 288 raw-bit proof.

This batch subsequently passed the unified build and same-JAR packaged acceptance below. Bound/external engines and full public chat/session/SSE migration remain unsupported here; the bridge whitelist is unchanged.


## Unified 6511-test packaged checkpoint

Root fresh clean verify actually exited 0: 135 classes/6511 tests/zero failures/errors/skips. Immutable JAR `/tmp/weknora-java-public-delete-concurrency-verified-build/backend-java-0.1.0-SNAPSHOT.jar` is 152658608 bytes, SHA256 `e30e96ea33105cf4492aafe5aa3bc42c919fdc80d7e6579958999b168af83cdd`. This follows a preserved first run that omitted required native-library parameters; the successful rerun did not change the frozen source.

The native public Hybrid single/go packaged runs against that exact JAR both actually exited 0 (sessions 32180 and 45227). Each verifies 11 public HTTP requests, five real embedding-provider calls and four physical vec/FTS entries, JWT own/shared GET+POST, retrieve/chat/KB-scope API-key gates, resource-URL gate, revoked share, exact Go DTO and Hikari1. Logs `/tmp/weknora-java-public-delete-concurrency-hybrid-package-single.log` and `/tmp/weknora-java-public-delete-concurrency-hybrid-package-go.log`. These synchronous runs keep processing/tasks disabled; actual bounded scheduling and blocked-query deadline/cancellation proof is the separate 18-case physical PostgreSQL differential above.

The same-JAR Graph single/go runners also exited 0, and root reports its other 18 final process acceptances exited 0. The unified 22-process manifest is `/tmp/weknora-java-public-delete-concurrency-package-final-manifest.json`; all 686 frozen non-Markdown source/test/POM files remained unchanged through the package checks. Root retains a first-attempt profile/go startup pool-borrow timeout as an unresolved risk even though the identical isolated rerun passed; no startup fix is claimed here.
