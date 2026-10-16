# Public URL import migration

The native `POST /api/v1/knowledge-bases/{id}/knowledge/url` controller is enabled by `weknora.knowledge.processing.enabled=true`. It uses the original authenticated caller for grants, quota and attribution, and the authorized KB owner for document/tag/task writes. Go bridge routing is unchanged: enabling the bridge does not automatically give this endpoint to Java.

## Production behavior

- The URL request uses the Go anonymous struct schema, typed null/duplicate semantics, first JSON value binding and required URL validation. Permission checks run before binding; the ingest API key route gate precedes controller execution.
- SSRF admission uses the server whitelist and whitelist-only policy. The original URL is retained for source and MD5 hashing. No document fetch happens in the admission call.
- Known path extensions or file hints select `file_url`; other links select `url`. Files use filename/type inference and import configuration checks. Web links validate processing overrides without imposing the file import gate. FAQ rejects file links while permitting web links, matching Go.
- Web duplicates use the actual Go repository's retained hash predicate and refresh creation/update timestamps before returning 409. Go currently has no effective `file_url` duplicate branch; Java preserves that behavior.
- Quota admission checks the caller tenant retained by Go `WithExecutionTenant`, including shared KB operations. SQL TEXT process configuration is ignored by Go's byte-only SQLite scanners; native URL admission preserves this distinction from BLOB/PostgreSQL JSON.
- New rows persist before tag validation. Invalid tags can leave a pending document with no task. Document enqueue failure retains a failed document and returns HTTP 201/success, with failed business audit. These are intentional Go checkpoints, not transactional rollback.
- Tasks preserve URL/file fields, empty URL-admission `request_id`, language, effective multimodel/question settings, default question count, queue/retry/deadline and optional table summary task. Business audit retains the original actor and positive API key identity/name.

## Actual Go differential evidence

`src/test/fixtures/knowledge_url_http_fixture.go` runs the production Gin handler, permission middleware, services and repositories, local storage configuration, miniredis and real Asynq admission. It captures full HTTP responses, document columns including NULL/default distinctions, tag relations, quota, tasks/options and business/denial audits. The fixture injects a trusted caller context; separate Spring tests cover real JWT and API key filters.

SQLite export: `/tmp/weknora-go-url-import-20261016-first.json` (78 scenes, actual exit 0), byte-identical to `src/test/resources/go-knowledge-url-http.json`.

SQLite and real Spring first successful combined run: `/tmp/weknora-java-url-admission-20261016-sixth.log`, 91 tests (78 differentials plus 13 Spring tests), failures/errors/skips 0, actual exit 0. Four Spring methods are new URL admission workflows; the other nine retain reparse coverage. The subsequent full build includes the final driver-neutral SQL JSON comparison correction.

PostgreSQL: `scripts/postgres-url-parity.py` owns a loopback-only disposable database, applies all 115 Go migrations and Java queue SQL, exports Go scenes independently and runs `KnowledgeURLPostgresGoParityIT` with Hikari pool size 1. Final 78 scenes passed with actual exit 0; artifacts `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-url-iudq9yrm`, runner log `/tmp/weknora-postgres-url-admission-20261016-second.log`. First PostgreSQL failure was the test harness serializing JDBC PGobject wrappers instead of their JSON values; the original 38-failure report remains at the sibling `weknora-postgres-url-bsgarnzy` artifact directory.

Other first attempts are retained: a missing test import/constant (compile failure), missing transaction-manager test wiring (78 errors), wrong test authentication attribute (78 failures), and the first behavior comparison (4 differential failures plus 2 Spring failures). Fixes included the production API gate, positive API-key audit identity and SQLite TEXT scanner semantics; JSON ordering and native SQL task status assertions were test harness corrections. Assertions still compare complete semantic JSON, all captured database fields and NULL values.

## Remaining strict alignment requirements

This endpoint is not fully verified. The current corpus includes Unicode, raw path/query spaces, backslashes, malformed path escapes/ports and ASCII controls, but does not cover every Go `net/url` versus Java URI behavior, raw-query escapes and all userinfo/host variants, all SSRF whitelist/DNS/settings races, concurrent duplicate admission, every database/storage failure checkpoint, tracing injection or all parser/model/index/storage variants. These remain requirements of full alignment, not accepted exceptions. Existing document worker transaction, quota accounting, external bound-index and queue/Lite differences also remain.

The public endpoint uses the already migrated URL/file_url document worker. Some remote-source network and failure behavior still differs from Go; passing admission does not prove successful processing for every accepted URL. Full build and same-jar package evidence will be recorded in the batch checkpoint. Do not treat old test counts or this bounded corpus as full backend replacement acceptance.

## Extended URL parser regression

After the first 78-scene pass, 16 more production Go HTTP scenes exposed 11 differences in raw path/query spaces, backslashes, invalid escapes/ports and control-character error text. Original red observations remain at `/tmp/weknora-java-url-import-20261016-extended-differences.json` and the corresponding probe log. The adapter now escapes URI-incompatible path/query characters only for Java policy/path parsing, preserves the raw source/hash/task value and Go-decoded filename, and preserves Go parse error text. The official fixture and SQLite resource contain 94 scenes. Final SQLite/Spring combination is 107 tests, zero failures/errors/skips, actual exit 0, log `/tmp/weknora-java-url-admission-20261016-eighth.log`. The previous seventh log is a Maven invocation from the wrong directory, actual exit 1, and is retained.

The first frozen full build was deliberately stopped after these new counterexamples (actual Maven exit 143, owned ES stop exit 0). Its partial green reports are not a successful full build. New frozen layout/proof/manifest use the `url-admission94` prefix; final full and same-jar outcomes belong in the checkpoint. Historical 78-case results above remain bounded historical evidence.

The expanded PostgreSQL corpus also passed: 94 scenes, all 115 Go migrations, Hikari pool size 1, actual exit 0. Artifacts `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-url-sdun45p5`, runner log `/tmp/weknora-postgres-url-admission-20261016-final94.log`.

## Final frozen build and packaged acceptance

Full clean verify: 8,237 tests /169 classes, failures/errors/skips 0, actual exit 0. Final manifest `/tmp/weknora-java-url-admission94-20261016-full-manifest.json`, reports `/tmp/weknora-java-url-admission94-20261016-full-build/surefire-reports`. The owned ES8 container stopped with exit 0. Non-Markdown src/pom inputs were rechecked against the frozen proof with no differences.

Jar SHA256 `e76ea8315abf43fadec6da9f9deb867507c533842173f608712c3bc78d032c52`, 153,013,389 bytes. Frozen `processing_smoke.py --url-import --topology single` and `go` both actual exit 0: each 2 original uploads, 2 URL imports, 8 successful tasks, 4 embedding HTTP, 1 reader HTTP, 0 chat. Each also checks URL queue failure returns 201 with a failed document and no extra task. Independent SQL/source recheck confirmed four completed/one failed documents, four document plus four postprocess succeeded jobs, four exact float32 vec/FTS entries, original download bytes, correct owner quota and original Viewer/shared Contributor audit attribution.

Checkpoint `migration/url-import-checkpoint-2026-10-16.json` includes report hashes, input hashes, log paths, jar hash and both package modes. Route is in_progress; no new verified routes. Global ledger is 106 in_progress/375 not_started/1 health implemented of 482. No deployment or development-instance restart occurred.
