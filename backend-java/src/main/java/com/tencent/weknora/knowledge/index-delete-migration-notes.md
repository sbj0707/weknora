# Native index deletion migration

The next batch adds opt-in `index:delete` to the production Java task registry. It uses `low`, 11 total attempts (Go MaxRetry10), and a one-hour deadline. No document counter, processing span, storage charge or embedding HTTP request belongs to cleanup. An admitted producer helper is available; its public tag-delete entry point and complete lifecycle are still pending; this worker is not an independently verified public feature.

`IndexDeleteHandler` parses typed payload fields, checks the durable task owner, resolves the captured backend selection and embedding model, and processes at most100 payload IDs per batch. `IndexDeleteRepository` checks the current SQL lease, cancellation and attempt, resolves owner/KB bindings including soft-deleted chunks and KBs, preserves input order/duplicates after narrowing scope, and deletes all primary/question sources for those chunks in the current batch transaction. PostgreSQL locks KB and chunk rows. SQLite uses the caller transaction. A later batch failure retains earlier committed batches.

A queue store grant is not a grant for all chunks in a shared physical store. Actual Go production worker playback showed `wrong-kb` and `foreign-chunk` payloads deleting physical vector and FTS indexes outside the supplied owner/KB. Go now filters chunk IDs by owner, KB and KB owner, deliberately including tombstones so tag deletion remains functional. The original corrected-schema/BLOB-model unsafe oracle is preserved at `/tmp/weknora-go-index-delete-original-blob.json`; production-current27-case oracle is `src/test/resources/go-index-delete.json`. Earlier TEXT-model and missing-SSRF-whitelist fixture runs remain in /tmp; formal fixtures use actual Go BLOB model parameters and an explicit loopback whitelist.

Go resolves embedding capability without checking the stored model type. Java's ordinary embedding API remains strict; `ModelClientFactory.embeddingForCleanup` preserves Go's cleanup-specific behavior while retaining owner/builtin visibility, status, credentials and model URL policy. No inference request is sent by cleanup.

## Evidence

- 27 production Go worker cases, actual SQLite metadata/vec0/FTS: normal, empty/null IDs, duplicates,205 IDs, second-batch/first-batch failure, no engines, unsupported engine, missing/foreign/wrong-type model, malformed typed payload, wrong KB/foreign chunk/same-owner-other-KB/mixed scope/missing chunk/soft-deleted chunks+KB/changed KB owner. Exact ordered batches, model dimensions, surviving rows and all three physical row counts are compared. Error presence is compared; driver error text is not a byte-level parity claim.
- 1 additional native lease/cancellation fence case.
- 3 production Spring/Hikari1 tests: shared multipart upload→actual model HTTP→indexes→soft-deleted cleanup with captured model, wrong KB/changed owner preserve all published indexes, no engines still checks model and bound stores return an explicit unmigrated error. No extra embedding HTTP, no counter or storage changes, original actor audit preserved.
- Final focused **41 tests / 3 classes / zero failures/errors/skips**, actualexit0; `/tmp/weknora-java-index-delete-final-test.log`. Adds9 actual Go FAQ/tag-deletion→Asynq producer cases and1 native snapshot/fresh-delivery/owner check. Only the admitted producer envelope and options are replayed; Java public tag CRUD and chunk mutation are validated separately in the subsequent Tag batch, outside this helper evidence. Go grants come from the real access resolver; queue failures are best effort, as Go currently behaves.
- 115-migration PostgreSQL/ParadeDB integration actualexit0, one IT with zero failures/errors/skips:10uploads/38jobs/36succeeded/2canceled/20embedding/17chat. The three new cleanup tasks reject the wrong KB and remove native halfvec sources for soft-deleted owner/shared documents, retaining other indexes and storage/counters. `/tmp/weknora-java-postgres-index-delete-pipeline.log`, artifact `weknora-postgres-processing-8kwowizb`.
- Affected Go repository/service/handler suite passed with `-tags sqlite_fts5`; `/tmp/weknora-go-index-delete-scope-suite.log`.

```sh
# Run from repository root; fixture only owns disposable SQLite files.
go run -tags sqlite_fts5 backend-java/src/test/fixtures/index_delete_fixture.go /tmp/go-index-delete.json
# Candidate packaged worker: queue rows are explicit isolated test fixtures;
# this does not exercise the unmigrated public tag-delete producer.
python3 backend-java/scripts/processing_smoke.py /path/candidate.jar /path/vec0 --topology single --index-delete
python3 backend-java/scripts/processing_smoke.py /path/candidate.jar /path/vec0 --topology go --index-delete
```

## Pending boundaries

Default SQLite and PostgreSQL backends are wired. Bound vector-store selection and the other Go engines remain explicit unmigrated errors; payload snapshots are not replaced with the current KB's settings. The public producer, full tag/KB/document deletion, model provider breadth, all malformed/duplicate task JSON, physical orphan cleanup after a hard purge, source moves/rebinding races and full restart/cutover remain pending. Go's remote engine cleanup is not transactionally fenced by this SQL scope lookup. Native one-backend batch rollback is stronger than Go's separate physical/metadata writes; partial in-batch driver failures require further differential coverage. SQL delivery/retention differs from Asynq/Redis. No queue backlog or production traffic was switched.

The final immutable5,798-test/124-class jar includes this41-test batch and passed all16 packaged flows, including both cleanup topologies. The earlier31-test candidate remains historical evidence. The31-test candidate jar152,516,038bytes passed both packaged `--index-delete` runs with actualexit0, each2uploads/7succeeded jobs/2embedding HTTP/0chat; wrongKB preserves indexes, own/shared soft-deleted cleanup leaves zero metadata/vec/FTS rows. Logs `/tmp/weknora-java-index-delete-package-{single,go}.log`. It predates the new producer helper. Global `VALIDATION.md` records completed artifacts separately.

Latest checkpoint: `/tmp/weknora-java-index-rag-verified-build/backend-java-0.1.0-SNAPSHOT.jar`,152,526,858bytes. Samejar cleanup single/go actualexit0: `/tmp/weknora-java-index-rag-package-index-delete-{single,go}.log`; each2uploads/7succeeded jobs/2embedding/0chat. Full5798 report and16 package run evidence are in `VALIDATION.md`. Public Tag work is subsequent, not included in this artifact.
