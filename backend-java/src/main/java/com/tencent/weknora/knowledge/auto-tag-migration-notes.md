# Native automatic tagging

2026-10-15: `knowledge:auto_tag` uses the production existing knowledge/tag tables and model HTTP. It is enabled only with native knowledge processing, then only for a document KB with `auto_tag_config.enabled` and text-like postprocess input. No constructor DDL and no counter receipt are needed: auto tagging never owns a pending-subtask slot.

## Actual Go evidence

52 real Go service scenarios compare exact provider requests, sorted persisted tag IDs, and span name/status/input/output/error code. This invokes production Go repositories, model clients and tracker on actual Go SQLite migrations. It does not claim a real Asynq worker or whole Go HTTP router differential. The service does not inspect retry metadata, so retry/error decisions are compared directly. Eight real Go Post handler cases additionally compare actual Asynq enqueue payloads/options/order, counters/state and postprocess trace. Six Spring Hikari1 cases use real production upload, Doc/Post, classifier HTTP and cancellation HTTP.

The final dedicated test log is `/tmp/weknora-java-auto-tag-final-test.log` (52 + 8 + 6 tests, all passed). Go source-fence regressions passed `/tmp/weknora-go-auto-tag-fences-test.log`; full affected Go and the new Java package/PG acceptance are being rerun after Graph integration. The previous 5,049-test jar did not contain this module.

## Rules preserved

- Up to 500 candidates, ordered `sort_order ASC, created_at DESC, seq_id DESC`. Capped candidate sets still classify their stable prefix. Existing tags are preserved; skip-if-tagged defaults true.
- Config model ID is trimmed and falls back to KB summary model. Prompt uses numbered names and 16,000-rune head/middle/tail sampling. The real Go `ListChunksByKnowledgeID` repository is text-only, even though its content helper accepts image kinds. Java preserves that actual production behavior.
- Chat temperature 0.1, max_tokens 1024, thinking false. Typed Go JSON parsing accepts raw/fenced/balanced output; missing/null confidence means 1, percentages over 1 divide by 100, values clamp to 0..1, threshold 0.75. Scores sort stably, invalid/duplicate IDs are dropped; max_tags defaults 3 and caps 10.
- Additive, scoped, idempotent relations use conflict-do-nothing and never remove manual tags. Cancellation/failed/deleting state and disappearing/out-of-scope tags reject persistence. Go's existing canceled-knowledge repository guard remains a retryable AUTO_TAG_PERSIST_FAILED, including business cancel during Chat; Java matches it.
- Queue summary, MaxRetry2 (3 executions), timeout2min. Enqueue is best effort. AutoTag goes before summary/question/graph in the committed native handoff and contributes no counter. Its failure cannot hold parsing in finalizing.

## Go baseline corrections

Original 43-case unsafe output remains `/tmp/weknora-go-auto-tag-before-fences.json`. Actual output demonstrated classification from same-document/wrong-KB chunks and publishing obsolete results after chunk revision or a successor attempt. Go and Java now check ownership/KB binding before model input and recheck attempt/chunk revisions, content/type/enabled/order after Chat. Missing or foreign documents are rejected before opening a span to avoid orphan or other-owner writes. The corrected fixtures are committed as `go-knowledge-auto-tag.json`; all scenarios are strictly replayed.

A new derived summary arriving during classification is allowed when the document chunks remain current. Rejecting that description-only change would drop valid tags in the normal parallel summary pipeline. Its actual Go case is included.

## Explicit limits

Java lease/cancel fencing is stronger than the direct Go service; task payload owner must match the SQL queue owner. Provider/dependency error messages and timestamps are not byte-level comparisons. Arbitrary Unicode JSON key folding, duplicate JSON properties that collapse in a tree, every persistence/concurrent-write fault, all cloud/graph index engines, Redis queue cutover/restart, public tag CRUD and KB profile refresh remain outside this module's verified scope. It remains in progress until these broader task/runtime paths are aligned.
