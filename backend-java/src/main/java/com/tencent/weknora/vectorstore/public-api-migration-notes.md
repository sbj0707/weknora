# Public vector-store management: native migration in progress

This slice adds native management endpoints; the previous internal metadata / registry / bound-ES retrieval migration did **not** implement these endpoints. The previous shared 7,712-test / 40-process immutable checkpoint predates this slice. Global endpoint verification remains incomplete.

## Production surface

| Method | Route under `/api/v1/vector-stores` | Behavior |
| --- | --- | --- |
| GET | empty | Owner-scoped DB stores, live env projections first, masked credentials |
| GET | `/types` | Actual Go form metadata, including environment replica default |
| GET | `/:id` | Owner lookup or env projection; fixed 404 / failed-query envelopes |
| POST | empty | Typed binding, required/index/SSRF validation, DB+startup-env duplicates, actual probe/version, encrypted persistence, best-effort engine registration |
| PUT | `/:id` | Lookup before binding; name-only update; credentials, engine and index immutable |
| DELETE | `/:id` | Env read-only; owner row lock and live KB count in one transaction; soft deletion; unregister after commit |
| POST | `/test` | Typed raw config, allowlist and SSRF before probing; no persistence |
| POST | `/:id/test` | Trusted saved/env credentials; detected DB version saved best effort |

Go has no separate vector-store `/health` route. Its two connection-test endpoints provide the health behavior. The compatibility bridge allowlist is unchanged: bridge-enabled requests continue to Go. Native acceptance explicitly uses bridge=false.

All eight method/route combinations require `manage_vector_stores` or full access for API keys. JWT workspace reads require Viewer; create, update, delete and both tests require Admin. Machine management authority does not inherit an unrelated KB allowlist or a Viewer human role. Native registration is in `AuthFilter` / `NativeRoleGuard`; public handlers still enforce owner isolation. There is no synthetic administrator floor for reads.

## Supported and explicitly unavailable

The complete real connection/create/register/read/name-update/delete path was exercised against owned Elasticsearch **8.14.0**, SQLite metadata, and PostgreSQL metadata with all **115** Go migrations and Hikari maximum pool size 1. ES requests use the existing DNS-pinned transport. Its added `getWithoutRedirects` operation preserves Go's connection-probe contract: a 302 is a failed health test, never followed. Root probes use Basic auth only when username is nonempty, inspect at most 4,096 bytes, and treat malformed or untyped version JSON as an unknown version while connected.

Env projections include all Go drivers even where runtime access is not yet native. SQLite and default-connection PostgreSQL env probes return Go's empty version without a remote dial. Non-ES remote engines and standalone PostgreSQL connection management return an explicit 503 / code 2201 capability error. They do not return fake successful connection tests. Existing ES7 metadata may be displayed, but native ES7 retrieval remains unavailable. The `/types` metadata remains the Go UI contract; listing a type does not claim its native runtime is migrated.

This management slice does not enable bound-store document/background publication, other remote engine adapters, external graph stores, or a new KB vector-store binding API. Registry invalidation remains per process, as in Go. Go's documented KB-create versus store-delete binding race remains; no distributed invalidation or cross-service transaction is claimed. Public list failures currently use a safe generic internal-error diagnostic rather than reproducing every Go SQL Scanner error string; malformed credential **owned lookup** failures have actual strict comparison coverage below. Broader broken-schema/driver-error diagnostics, proxy and URL-userinfo transport configurations, and concurrent bind/delete races remain unverified. No compatibility claim is made for those configurations.

## Actual Go differential evidence

Generator: `backend-java/src/test/fixtures/vector_store_public_fixture.go`, built with `-tags sqlite_fts5`. It invokes actual Go handler, service, repository, engine factory, registry, role middleware and API-key route authorizer; HTTP/SQL and register/unregister/build side effects are captured. Its normal path uses the owned real ES8. Controlled HTTP probes intentionally exercise 401, 302, malformed/numeric/trailing/oversize/missing-version responses and record auth semantics; these cases are not described as physical ES failures. A separately named injected factory failure verifies Go's persist-then-best-effort-register contract.

Formal fixture: `src/test/resources/go-vector-store-public.json`, **133 cases**. Request schemas and type metadata were reflected/generated from the same production Go structs. Cases include null/empty/duplicate/type/first-value binding, immutable settings, default index duplicates, env duplicates and rich secret projections, soft-deleted/foreign owner, live KB bound deletion, role/API policy, SSRF rejects, version persistence, registry side effects, corrupted credential lookup before update/delete/test binders, reserved ciphertext prefixes on create, a controlled SQL trigger causing post-rename re-fetch failure, and duplicate-scan failure. The trigger is an explicit fault injection, not presented as an observed concurrent production edit.

Native comparison validates complete JSON including null/missing fields, actual SQL rows, encrypted secret prefixes and decrypted values, actual factory HTTP requests, exact registry calls, and the number of vector-store SELECT operations during the request. The Go generator counts real GORM Query callbacks; the native replay observes real prepared reads through a DataSource wrapper. This caught an extra create-time SELECT and keeps Go's two-read update / delete boundaries. Only generated UUIDv4 identities and encryption nonces are compared semantically; SQL rows are keyed by their canonical primary keys. API arrays and nested JSON strings are not sorted or normalized. PostgreSQL uses the real PG Go oracle, not a SQLite fixture relabeled as PG.

Retained failures:

- Go generator first compile failed due to fixture API/type assumptions; second failed on missing required KB fixture fields. Both logs remain under `/tmp/weknora-go-vector-store-public-{first,second}.log`. Production Go was unchanged.
- Native first 84-case run had 7 failures (missing route registrations and generated UUID SQL row order). Expanded 125-case first run had 10 failures (route registrations, redirect semantics and Go malformed IPv6 diagnostic). Original logs remain; fixes changed production transport/registration/error mapping, not the expected responses.
- The initial full-build command used the wrong Jieba property name and was intentionally terminated with actual exit **143**. Log `/tmp/weknora-java-vector-public-full.log` is retained. The corrected command uses `weknora.jieba.library`; this is separate from a business assertion failure.

## Focused and real DB checks

Commands use Java 21 and fresh isolated Maven output directories.

```sh
WEKNORA_ES_PARITY_URL=http://127.0.0.1:58599 \
  go run -tags sqlite_fts5 backend-java/src/test/fixtures/vector_store_public_fixture.go /tmp/weknora-go-vector-store-public-final129.json

mvn -q -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-vector-public-candidate-build \
  -Dweknora.es.url=http://127.0.0.1:58599 \
  -Dtest=VectorStorePublicGoParityIT,VectorStorePublicPipelineHttpIT,VectorStoreMetadataGoParityTest,VectorStoreRegistryGoParityTest,NativeRoleGuardHttpIntegrationTest,ModelTransportSecurityTest,ParserRedirectGoParityTest,ModelURLPolicyTest,Elasticsearch8ContextGoParityTest \
  clean package

python3 backend-java/scripts/postgres-vector-store-public-parity.py \
  --es-url http://127.0.0.1:58599
```

The first command exited **0**. The fresh candidate package exited **0**, independently inspected XML **238 tests / 9 classes / 0 failures, errors, skips**: 129 actual-Go cases, 6 full authentication Spring cases, and 103 existing metadata/registry/security/transport/SSRF/context regressions. The prior 225-case run also exited 0.

PG final129 runner exited **0**, with actual Go129 and native129, full115 schema / Hikari1. Artifacts: `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-vector-public-3w61cu3l`; log `/tmp/weknora-java-vector-public-postgres-final129.log`. This PG run precedes the final formatting and the engine-filtered duplicate-read query improvement; the latter retains all129 expected outcomes and is included in the candidate package. A final fresh PG replay will be recorded below if repeated.

## Fixed candidate process acceptance

Script `backend-java/scripts/vector-store-public-smoke.py` takes `jar vec --es-url --topology single|go [--root ...]`. It only uses a new temporary SQL DB and random owned index namespaces. Its `finally` stops only its Java child and deletes only its generated indices. It never deletes all ES data or uses a development DB/volume.

Both configurations intentionally have **tasks=false**, processing=false, bridge=false, Neo4j=false. They test synchronous management, not Go-compatible worker queues.

Candidate jar `/tmp/weknora-java-vector-public-candidate-build/backend-java-0.1.0-SNAPSHOT.jar`, SHA256 `1f42bedd5633d13e4df19148cf0c569112e78a3068588610f227128218eb7fb1`.
Runner SHA256 `b7ab9d3ad02bfa41fe761e34ebd875098e4d278a24108dc22b1dead54fe80bf8`.

Both actual process exits were **0**. Each made **40 HTTP requests**, created and soft-deleted **2 stores**, and verified **2 real physical indices** remained intact after metadata deletion. Checks include JWT / API scopes, Viewer/Admin, env reads/readonly, encrypted SQL, actual ES8 factory-created indices, raw/saved health, detected version, name immutability, foreign owner, duplicate and bound-KB rejection. Logs `/tmp/weknora-java-vector-public-package-{single,go}-first.log`. Candidate acceptance is not a global full verification claim.


## Final SQL-boundary correction (after the first candidate)

A fresh default `clean verify` completed with actual exit **0**, independent **159 classes / 7,712 tests / all zero** at `/tmp/weknora-java-vector-public-full-corrected-build/surefire-reports`; its 1,133 non-Markdown source/config/migration input hashes were unchanged. Log `/tmp/weknora-java-vector-public-full-corrected.log`. That successful build preceded the following final corrections and is not presented as their validation:

- Actual Go accepts credential inputs beginning `enc:v1:` and returns a newly created masked in-memory DTO; an additional native strict reload had turned that 201 into 503. Create now returns its in-memory projection with Go-compatible omitted fields and no second SQL read. Ordinary subsequent reads remain strict. Actual Go now includes both password and API-key cases.
- Query-count oracle first failed native **16 of 131** cases on the extra create SELECT. Unlike create, Go's rename handler **does** re-fetch (two reads total); that boundary was retained after verifying the actual source and trace.
- A controlled after-rename SQL trigger corrupts the stored credential before the handler re-fetch. Actual Go warns and returns **200 / data:null**, while preserving the committed name update. Native initially returned 503. Its update service now preserves Go's acknowledgment and logs the re-fetch failure. The resulting native pre-fix 132-case run had **17 failures**; logs `/tmp/weknora-java-vector-public-sql-boundaries-before{,132}.log` and reports are retained.
- A broken row encountered during duplicate checking returns Go's **500 / code1007 / failed to check for duplicates**, before any ES request or insert. This is included in the final133 oracle.

The intermediate full131 attempt was deliberately terminated (actual **143**, log `/tmp/weknora-java-vector-public-final-full.log`) before these final SQL-boundary changes. No incomplete run is counted as passing. The Go132 generator's first attempt also had a fixture-only missing field compile failure; `/tmp/weknora-go-vector-store-public-final132.log` remains, followed by successful corrected exports. Production Go is unchanged in this public-CRUD slice.

Final focused command uses the same nine classes as the earlier candidate but fresh directory `/tmp/weknora-java-vector-public-final133-build`. Actual exit **0**, independent **242 tests / 9 classes / 0 failures, errors, skips** (133 public differential + 6 production Spring + 103 existing regressions). Log `/tmp/weknora-java-vector-public-final133.log`.

Earlier bound retrieval and physical ES tests were also replayed: **61 tests / 3 classes / all zero**, actual exit **0**, `/tmp/weknora-java-vector-public-hybrid-regression-second-build/surefire-reports`. The first command omitted the two required fixture-path properties and failed with fixture setup NullPointerExceptions; its log/reports are retained. The corrected command supplied `weknora.es.fixture=.../go-vector-store-es.json` and `weknora.es.hybrid.fixture=.../go-vector-store-hybrid.json`. The 61-run predates the final create-only / swallowed-update-error corrections; the internal strict metadata default and transport are unchanged by them.

The final runner expands the old 40-request acceptance to assert denial without management capability on **all eight route/method combinations**, and explicit 503/code2201 gates for valid configs of all **six** unimplemented remote engines on both create and raw-test. The original passing runner is preserved at `/tmp/weknora-vector-public-smoke-original40.py`, SHA256 `b7ab9d3ad02bfa41fe761e34ebd875098e4d278a24108dc22b1dead54fe80bf8`. The intermediate expanded runner SHA256 was `28f6273e791294d122405fdaaaa4e454c7012c816939294749927fa4ef48901e`; the final PG-capable runner is recorded below. These are expanded assertions, not relaxed assertions after a failure.


## Final accepted snapshot

Final Go export `/tmp/weknora-go-vector-store-public-final133.json` exited **0** and is the formal resource. Both generated request schemas and type metadata were independently checked equal to the production resources. Final Go/Java PostgreSQL replay also exited **0** after all final source changes: **133 strict cases / 115 Go migrations / Hikari1**, independent XML all zero at `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-vector-public-r4028882/build/surefire-reports`. Log `/tmp/weknora-java-vector-public-postgres-final133.log`.

A fresh final `clean verify` then exited **0** with independently inspected **161 classes / 7,851 tests / zero failures, errors, skips**. This selects Maven's default test-name patterns **plus** the new public differential IT133 and production Spring IT6; other external-infrastructure IT classes retain their separate explicit-run evidence above. Reports `/tmp/weknora-java-vector-public-final133-full-build/surefire-reports`; log `/tmp/weknora-java-vector-public-final133-full.log`.

```sh
mvn -q -f backend-java/pom.xml \
  -Dweknora.build.directory=/tmp/weknora-java-vector-public-final133-full-build \
  -Dweknora.es.url=http://127.0.0.1:58599 \
  -Dweknora.sqlite.vec.extension=/tmp/weknora-retrieval-native/vec0.dylib \
  -Dweknora.jieba.library=/tmp/weknora-jieba-formal-rebuilt/libweknora_jieba.dylib \
  -Dweknora.jieba.dict=/tmp/weknora-jieba-formal-rebuilt/dict \
  '-Dtest=Test*,*Test,*Tests,*TestCase,VectorStorePublicGoParityIT,VectorStorePublicPipelineHttpIT' \
  clean verify
```

Fixed jar `/tmp/weknora-java-vector-public-final133-full-build/backend-java-0.1.0-SNAPSHOT.jar`, **152,935,360 bytes**, SHA256 `c7b5e454c6d6fcb421e16ed6332383a5640f3c1444f0db0e5535b94c1d17d792`.

All **six** final same-jar process runs exited **0**:

| Configuration | Requests | Actual acceptance |
| --- | --- | --- |
| Management SQLite / single | 58 | 2 created + 2 soft-deleted stores; 2 real ES indices retained after config removal |
| Management SQLite / go | 58 | Same |
| Management PostgreSQL / single | 58 | Full115 schema, real JWT/API authentication, Hikari1, same ES and SQL assertions |
| Management PostgreSQL / go | 58 | Same |
| Bound public hybrid / single | 14 | Real ES8 and model HTTP, own/shared/API scope, empty bound store never falls back |
| Bound public hybrid / go | 14 | Same |

All six explicitly use **tasks=false**. PostgreSQL here is the actual **metadata database**, while physical vectors/documents for the bound engine remain in the real ES8. The management runner deliberately keeps the SQLite env projection for identical read-only projection assertions; this does not assert PostgreSQL engine connectivity or default retrieval behavior. The two hybrid runs separately check their real default SQLite vec/FTS data and bound ES isolation.

Final runner `backend-java/scripts/vector-store-public-smoke.py` supports `--postgres`, creating/stopping its own `paradedb/paradedb:v0.22.6-pg17` container and applying all115 Go migrations. It uses the owned container's real `psql` for fixture data and the production jar's real PostgreSQL driver/beans for HTTP behavior. No Python PG driver or fake SQL backend is used. Final runner SHA256 `6d7905f7728c0e3f954675a58af459c15cc1fc2e7eecf8427dae9c1cdd5af0ba`.

The first PG runner preflight failed solely because a fixture KB ID was 39 characters while the actual PG schema limits it to36. The corrected runner uses a real UUID-sized KB ID; API/status/data assertions were retained. Original runner `/tmp/weknora-vector-public-smoke-pg-preflight-first.py` and original failed log `/tmp/weknora-java-vector-public-runner-pg-preflight.log` remain; the corrected candidate preflight exited0. These candidate preflights and the old40-request candidate runs are recorded separately and **not added** to the final six configuration count.

Manifest `/tmp/weknora-java-vector-public-final-package-manifest.json` records exact jar, runner and log hashes, actual process statuses, infrastructure details and prior attempts. Frozen-input proof `/tmp/weknora-java-vector-public-final133-source-proof.json` covers **1,133 non-Markdown main/test/resources/pom/config/migration/proto files**. Hashes were rechecked unchanged through all six final runs. Full-run input proof deliberately excludes scripts, whose independent hashes are in the manifest.

Only the newly owned ES container `weknora-vector-crud-es8-owned-20261015` at ephemeral port58599 and per-run owned PG containers were used. Every runner deletes only its random index namespaces and stops only its child Java and owned PG container. The shared ES container was stopped after final acceptance. No development database/volume, deployment, bridge allowlist or production routing was changed. No route is marked globally verified by this note; the listed runtime/diagnostic/concurrency gaps remain.
