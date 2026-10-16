# Native SQLite index and retrieval SPI

`SQLiteIndexRepository` operates on the actual Go `lite_embeddings` metadata,
contentless `lite_embeddings_fts` FTS5 and dimension-specific
`vec_embeddings_<dimension>` vec0 cosine tables. It does not introduce an
alternate Java document/index schema. Initialize it against a database whose
existing Go SQLite migrations have run. The constructor migration creates
metadata/indexes, rebuilds the older content-linked FTS table with existing rows,
repairs enabled image OCR/caption indexes and restores existing vector dimensions.

The deployment operator must choose an existing absolute native library path.
`SQLiteVectorExtension` unwraps each real JDBC connection, enables loading only
while loading that library, disables loading afterward and verifies `v0.1.6`.
No default application path or document/model-supplied extension is used.
This SPI is not automatically registered as a public API, processor or retriever
route. Its caller configures `WEKNORA_SQLITE_VEC_EXTENSION`, instantiates the
repository and calls `initialize()` when opting into this native backend.

The native C extension is compiled from the repository's already locked
`github.com/asg017/sqlite-vec-go-bindings v0.1.6` module cache, using the locked
`mattn/go-sqlite3` extension header. It contains no Go business process/bridge.
The source itself and architecture-specific binaries are not vendored. Go tools
and a C compiler are needed for this reproducible developer build; the deployed
Java process only needs the built platform-specific library. A prebuilt
operator-provided matching library is also accepted. The script has been run on
macOS arm64; its Linux command is included but has not been executed here.
The upstream [sqlite-vec v0.1.6 MIT license](https://raw.githubusercontent.com/asg017/sqlite-vec/v0.1.6/LICENSE-MIT)
is included under `src/main/resources/licenses` and copied beside the compiled
library. The binding cache lacks its own license file; the notice is the exact
upstream extension license.

From the repository root:

```
backend-java/scripts/build-sqlite-vec.sh /tmp/weknora-sqlite-vec-native
go run -tags sqlite_fts5 ./backend-java/src/test/java/com/tencent/weknora/retrieval/retrieval_fixture.go /tmp/go-retrieval.json
```

`IndexWriter.prepare(dimensions)` creates vec0 dimension tables outside the
processing transaction. `batchSave(Connection, entries, sourceVectors)` and
`deleteByKnowledge/deleteByChunk/deleteBySource` reuse the caller's transaction
and reject autocommit. The caller owns chunk rows, source/owner/attempt fencing,
status updates and commit/rollback. Network embedding calls run before the
transaction; returned vectors are validated before preparing their dimensions.
An index SQL failure propagates and the caller must roll back. Go's old private
vec/FTS helpers ignored individual errors; Java deliberately does not report
successful indexing when a required index write failed.

Batch source deduplication preserves the first position and last entry for
`(source_id, source_type)`, then replaces metadata, FTS and vector rows together.
Changing dimensions removes the old vector. Float32 blobs use Go's exact
little-endian IEEE754 layout. `FloatVectors.validate` rejects empty, dimension
mismatched or non-finite model output. Index content drops NUL and invalid UTF16
surrogates; a valid replacement character remains. The actual model input is
separate: `IndexText.embeddingInput` ports Go's inline-base64 scrubbing and
20,000-rune cap, while the stored index content retains the unsanitized text.

FTS uses the production Go Unicode range tables for Han/space/punctuation/symbol,
overlapping Han bigrams and quoted OR content-only terms with single-Han prefix
matching. Vector filtering runs inside the vec0 top-k query before selection;
metadata filters for KB/document/tag and historical null enabled flags mirror
Go. SQLite threshold filtering runs afterward. The Go SQLite adapter does not
apply its excluded document/chunk fields; this internal SPI exposes the filters
it actually applies. Callers must authorize and restrict the requested KB IDs.
The index tables have no tenant column, so a public route must resolve permitted
KB scope before calling them. This package does not expose such a route.

The actual production Go fixture generator loads the original initial SQLite
migration and invokes the real repository constructor, batch save, disable/tag,
delete and retrieval methods. Its snapshot contains 11 mutation steps with 704
real vec0/FTS queries and 8 public hybrid-indexer embedding-input cases. Native
Java tests compare the actual Go-created metadata/index/FTS schemas, every
metadata field/row ID/order, all stored vector bytes and every hit/filter/order.
Scores permit at most four double ULPs (plus 1e-12 for scores near zero): Go uses
SQLite 3.46.1 while Xerial uses 3.51.3, and one BM25 score differs by 1e-10 at
approximately 566,710.65. Other numeric fields compare exactly. Additional tests
prove chunk+metadata+FTS+vector rollback, explicit SQL failure, transaction
requirements, native path validation and dimension/finite output validation.

From `backend-java` after building the extension (choose `.so` on Linux):

```
mvn -q -Dweknora.build.directory=/tmp/weknora-java-index-build -Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib -Dtest=SQLiteIndexGoParityTest,NativeModelClientsTest,ModelTransportSecurityTest,ModelClientResponsesTest,OpenAIResponsesParityTest,GoStreamHelpersParityTest,ChatFallbackParityTest,AnthropicMessagesParityTest clean test
```

Postgres is not implemented or validated by this phase. Its production schema
uses `halfvec`, dimension-specific HNSW casts and ParadeDB `pg_search` BM25
`content |||`/`paradedb.score`; replacing it with ordinary tsvector would not be
parity. No running Postgres instance or installed vector/pg_search extensions
were found locally. Copy/move and additional retrieval stores,
public retrieval/RAG wiring and the full asynchronous processing pipeline remain
separate work. Passing these SPI fixtures does not establish end-to-end upload,
embedding, indexing and public retrieval completion.

Document index-status and chunk inspection now have native read-only routes;
their actual storage evidence, access controls, validation and remaining graph/
PostgreSQL limitations are documented in [index inspection notes](../knowledge/index-read-migration-notes.md).

The isolated native-client plus SQLite-index `clean test` completed with
28 methods across eight classes, zero failures/errors/skips. The existing
24 model-client methods remain green.

## Hybrid and rerank foundations

The native fusion/MMR, passage preparation and rerank stage are described in [the differential fixture notes](../../../../../../test/java/com/tencent/weknora/retrieval/migration-notes.md). Their three methods compare 428 production Go algorithm cases and 12 production Go SQLite/model-HTTP cases, including 20 real model requests. These components are not wired to the public hybrid-search route. Chinese MMR requires an explicit native cppjieba library and the five locked dictionary files; `scripts/build-jieba.sh` compiles the existing gojieba v1.4.7 C++ dependency without a Go business process. No model-supplied library/dictionary path is accepted. The locked MIT notices are packaged.

Typed metadata supplement: 80 actual production Go `reranking.EnrichedPassage` / `ModelPassage` cases cover `ImageAttrs.Source` as the real `ImageAttrSource` struct (`schema`, `prompt`, `custom`), invalid scalar source, duplicate fields retaining the first type error, nulls, lone Unicode surrogates, generated questions and integer revision validation. The two decoder fixes preserve all original 428 algorithm cases and 12 SQLite/model-HTTP cases (3 prior JUnit methods passed in an isolated shadow classpath). Reproduce from the repository root with `go run backend-java/src/test/java/com/tencent/weknora/retrieval/rerank_metadata_fixture.go /tmp/rerank-metadata.json`. This is metadata/passage parity only; the public hybrid-search route and full hydration/grant integration remain pending.
