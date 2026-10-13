# Native retrieval and hybrid foundation

The native SQLite index/retriever remains based on the existing production Go
`lite_embeddings`, FTS5 and dimension-specific sqlite-vec v0.1.6 tables. Its
separate oracle covers 11 mutation states and 704 retrieval queries. PostgreSQL,
ParadeDB and other vector-store engines are not implemented by this adapter.

The hybrid foundation adds production per-list BM25 rescaling, highest-score
chunk deduplication, weighted RRF, rerank passage cleaning/enrichment and the
shared rerank stage. The stage preserves candidate budgets, graph exceptions,
provider passage clipping, threshold degradation, top-one fallback, composite
scores, FAQ boosting, stable ranking and MMR. Image/document enrichment uses an
ordered typed decoder so invalid known fields discard the same enrichment as
Go even if later duplicate fields are valid. Raw chunk metadata and internal
context headers must be retained separately by the future authorized hydrator.

Chinese MMR uses a small JNI wrapper around the exact C++ `cppjieba` dependency
locked by production `github.com/yanyiwu/gojieba v1.4.7`, including
`CutForSearch(..., true)`. Java calls no Go application/runtime/business code.
`backend-java/scripts/build-jieba.sh` builds from downloaded locked module
sources, copies the five default dictionaries and MIT license notices, and
supports Darwin/Linux. Native code is loaded only from an explicit absolute
operator path. The library and dictionaries are trusted deployment assets;
custom dictionaries must be valid cppjieba inputs. The default fixture uses the
locked default dictionaries, not a user-specific `JIEBA_DICT_DIR` corpus.

Reproduce actual Go fixtures from repository root:

```sh
go run backend-java/src/test/java/com/tencent/weknora/retrieval/hybrid_fixture.go \
  /tmp/go-hybrid-foundation.json /tmp/go-hybrid-wire.json
```

The Go generator creates temporary overlay tests and leaves production Go
source unchanged. The algorithm fixture contains 112 fusion cases, 180 passage
cases, 18 real Jieba token cases and 118 rerank cases (428 total). The rerank
algorithm fixture uses a score-supplying model stub to isolate the business
stage. The separate 12-case native SQLite fixture calls production Go embedding
and rerank clients over real loopback HTTP (20 requests), then compares raw
retrieval, fusion, passages, diagnostics, returned rows, indices and wire bodies
with Java using the native model factory and SQLite adapter. It includes
hybrid/vector-only/keyword-only modes and knowledge/tag filters. Its minimal
row assembly is a fixture setup, not the production result hydrator.

The Java foundation has two test classes and three methods, including native
path/lifecycle failures. Tests require explicit native assets:

```sh
backend-java/scripts/build-sqlite-vec.sh /tmp/weknora-sqlite-vec-native
backend-java/scripts/build-jieba.sh /tmp/weknora-jieba-native
```

Maven properties (Darwin paths shown):

```text
-Dweknora.build.directory=/tmp/weknora-java-hybrid-foundation-build
-Dweknora.sqlite.vec.extension=/tmp/weknora-sqlite-vec-native/vec0.dylib
-Dweknora.jieba.library=/tmp/weknora-jieba-native/libweknora_jieba.dylib
-Dweknora.jieba.dict=/tmp/weknora-jieba-native/dict
```

The public hybrid-search route has not been switched to this foundation.
Multi-KB grants/model compatibility/store fan-out, FAQ iterative retrieval,
production result hydration with parents/relations/neighbors/image children,
resource-reference rewriting and public request/error/response behavior remain
integration work. Successful foundation tests do not imply a complete public
inference/RAG migration or PostgreSQL validation.

Formal isolated validation on 2026-10-13 completed with `clean test`: 14 classes,
621 tests, zero failures/errors/skips. This is 33 existing native model/index
methods, three new foundation methods and the existing 585-test knowledge
content read HTTP suite after adding two pure authorized-row projection
wrappers. Those wrappers perform neither SQL nor authorization and do not
expand grants. Evidence: `/tmp/weknora-java-hybrid-foundation-clean-test.log`
and `/tmp/weknora-java-hybrid-foundation-build/surefire-reports`.
