# Manual knowledge migration (in progress)

Native routes are opt-in under `weknora.knowledge.processing.enabled=true`, including when file upload is disabled:

- `POST /api/v1/knowledge-bases/:id/knowledge/manual`
- `PUT /api/v1/knowledge/manual/:id`
- SQL worker task `manual:process`, queue `default`, three retries, 30-minute timeout.

Bridge routing is unchanged. No cutover or complete Go parity is claimed.

## Evidence completed

- 98 actual Go public Gin/service/SQLite/Asynq admission cases vs native MVC: DTO/binder/typed metadata, drafts, publish/update, ownership/shared permissions, API capability/allowlist, original caller audit, and enqueue failure effects.
- The same 98 admission cases over an owned PostgreSQL DB with all 115 Go migrations and Hikari maximum pool size 1 passed. `/tmp/weknora-java-postgres-manual-public-second.log`; XML in temporary `weknora-postgres-manual-oqvx3w9c/build/surefire-reports`.
- 45 actual Go manual-worker/provider HTTP/SQLite vec and FTS cases vs native task, including distinct nonconstant vectors and their physical float32 raw bits per SourceID; same-content independent chunks, real sequence IDs, links/offsets, quota, partial failure, outer retry, soft cleanup, attempt tracking, postprocess task options, and stage rows.
- Those 45 worker cases also passed over actual PostgreSQL halfvec/full 115 Go migrations/Hikari 1: `/tmp/weknora-java-postgres-manual-worker-third.log`, XML `/var/folders/pf/6ssfy5pj7fj9w1jyg4kpbmcr0000gn/T/weknora-postgres-manual-i1g8t66y/build/surefire-reports`. Original first/second failed logs are retained: the independent `chunks_seq_id_seq` must explicitly reset in each owned test case; `TRUNCATE RESTART IDENTITY` does not reset a sequence without `OWNED BY`. No production sequence change or expected-value normalization was used.
- 57 actual Go data-URI/remote Markdown/HTML image-resolution cases, real HTTP and recorded physical FileService calls: reverse successful-count limits (30 per syntax), 10 MiB limits, standard/raw/URL-safe base64, linked images, 64px/512-byte icon rules, MIME/duplicate parameters and magic signatures, failures, exact HTML normalization/host keep policy. The compressed fixture preserves exact strings and saves only 57 KiB. This parser oracle records storage calls; it does not pretend the recording seam is real storage.
- Four real native SQL claim→handler→settle checks confirm malformed `manual:process` bodies ACK successfully using original SQL bytes; malformed other task types retain their previous persistence failure.
- Latest combined 204 tests / 4 classes passed: `/tmp/weknora-java-manual-images-worker-second.log`, `/tmp/weknora-java-manual-images-worker-second-build/surefire-reports`.
- Nine isolated production Spring/API/model/physical SQLite/resource cases passed: own/shared publish, original caller audit, draft→publish and version retention, real stored image bytes/owner claims, concurrent summary/question slots, publicly updated source during embedding, replaced lease, authorization before binder, API-key ingest/allowlist gates, and shared-image last-claim physical cleanup. `/tmp/weknora-java-manual-production-nine.log`, `/tmp/weknora-java-manual-production-nine-build/surefire-reports`. Manual bypasses DocReader as the actual Go worker does; file-stage assertions are not applied to it.

## Baseline corrections and preserved behavior

Unsafe actual Go worker baseline is retained in `/tmp/weknora-go-manual-processing-before.json`. Conditional manual source guards now reject stale body/metadata/title/type/KB ownership, business cancellation or a newer attempt before indexing and after embedding. The embedding wrapper and stable post-dedup content/SourceID ordering apply only to manual contexts; other file/chunk contexts retain their behavior. Final broader Go regression is required after the current source is stable.

Manual processing deliberately persists chunks before embedding. Provider failure soft-deletes the newly written chunks; a quota failure leaves them without indexes. Postprocess-enqueue failure leaves already published indexes and retries, with accounting only after queue acceptance. This differs from the existing native file processor's atomic publication; it is not replaced by that file workflow. Current Go empty/quota branches can leave the root attempt running; the worker oracle preserves that behavior.

Go triggerManualProcessing adopts an image pass's modified body only when that pass returned at least one image. Icon-only removal / linked-image unwrapping without a saved image therefore does not change the manual body. Native image resolution has a separate `resolveForManual` path, and this distinction is covered by actual Go cases.

## Current remaining work and limits

- Independent skip-tests candidate packaged background-worker checks passed for both single/go topologies: each 3 documents / 4 publish deliveries / 14 succeeded jobs / 10 embedding and 6 Chat calls / 11 physical vectors and FTS entries / 600 stored image bytes. Logs: `/tmp/weknora-java-manual-package-{single,go}-first.log`. This candidate predates the image-download cancellation monitor and is not a new shared full-build checkpoint. The monitor-source focused 217 tests / 6 classes passed at `/tmp/weknora-java-manual-monitor.log` and matching build reports. The subsequent Image slice adds a real production PG own/shared/cancel pipeline; see the dedicated image migration notes for the current evidence and limitations.
- Native image bytes and resource bindings work for supported local storage. Bound/external vector stores and nonlocal/cloud physical storage remain explicitly unsupported by their existing resolver/index gates.
- The subsequent native `image:multimodal` slice connects manual image fan-out, OCR/description/attributes and fan-in for supported remote generic OpenAI-compatible vision. Legacy inline VLM, Ollama/local and other unverified capabilities retain explicit gates. Its dedicated image migration notes describe the actual Go, SQLite, PostgreSQL and process evidence; this does not make all manual/image behavior fully equivalent.
- The strict 45 worker replay uses Go pool size 1. Four separate actual Go capacity-5/out-of-order provider/cancellation cases and the native shared manual batch pool passed, including exact input-specific vector raw bits and blocked HTTP cancellation (`/tmp/weknora-java-manual-production-pool.log`, 56 tests / 3 classes). It does not establish all other file/enrichment tasks share Go's global ants pool semantics.
- Optional Langfuse/OTLP asynchronous middleware and nonempty tracing-carrier propagation are not established by the empty-carrier worker goldens.
- Source fences cover the observed expensive-provider races. The Go baseline still uses read-before-write checks, not a fully atomic revision CAS around every row/resource write; do not infer complete elimination of every possible race.

The earlier immutable 6511-test / 135-class, 22-process checkpoint predates these manual classes and does not validate them. Global validation, interface status and task ledger are maintained by the root agent.
