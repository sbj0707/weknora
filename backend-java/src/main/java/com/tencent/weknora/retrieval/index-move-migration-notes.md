# SQL knowledge index moves

SQLite now exposes the same metadata move as PostgreSQL: change `knowledge_base_id` and clear `tag_id` for every index row matching the exact source KB and document. The move retains physical IDs, source IDs/types, chunk/document identities, content, dimensions, enabled flags, creation times and vectors. SQLite FTS and vec0 rows are untouched; retrieval joins them to the updated metadata. Updating only the requested chunk IDs would differ from Go: these two Go repositories deliberately ignore the supplied chunk list, dimension and knowledge type. Service admission must still validate the complete transfer scope.

Both Java methods require the caller's explicit transaction and leave commit/rollback ownership to it. This lets the move executor retain Go's independent index checkpoint rather than joining the later chunk/tag/document mutations. The PostgreSQL move timestamp now comes from application time, as in Gorm's update callback, rather than the SQL transaction's `CURRENT_TIMESTAMP`. SQLite binds a Go-compatible UTC date string with up to nine fractional digits. The execution fixtures compare changed/unchanged timestamps and retained creation dates; they do not assert a specific dynamically generated instant, timezone spelling or nanosecond rounding.

`src/test/fixtures/knowledge_index_move_fixture.go` runs the actual exported Go SQL repositories against real SQLite FTS5/vec0 or PostgreSQL halfvec/ParadeDB. Thirteen scenarios per backend cover normal scope, nil/incorrect chunk lists, ignored dimension/type arguments, same/empty/quoted IDs, unrelated documents/KBs, disabled rows, already-target rows, SQL failure, rollback and repeated invocation. Java compares full selected metadata and physical vector identities/content plus six source/target/third-KB vector/keyword visibility samples at each of three stages (234 query samples per backend). Retrieval hits are sorted by source ID for set comparison; scores and unspecified tie order are not part of this fixture. Each execution must preserve its own complete FTS shadow-page bytes exactly, while cross-language comparison uses logical metadata, vector bytes and retrieval. Private FTS page encoding is not compared across different SQLite builds.

Actual Go/Java SQLite13 and PostgreSQL13 pass. PostgreSQL applies all 115 production versioned migrations and uses a Hikari pool of one. The focused SQLite/index/executor/foundation suite has 336 passing tests in four classes, with no failures/errors/skips. A two-dimensional physical store is used; this does not replace the broader existing retrieval tests or validate every possible dimension/data representation.

From the repository root:

```sh
go run -tags sqlite_fts5 backend-java/src/test/fixtures/knowledge_index_move_fixture.go /tmp/go-index-move.json
mvn -f backend-java/pom.xml \
  -Dweknora.sqlite.vec.extension=/absolute/path/to/vec0.dylib \
  -Dweknora.index.move.fixture=/tmp/go-index-move.json \
  -Dtest=KnowledgeIndexMoveGoParityTest test
python3 backend-java/scripts/postgres-index-move-parity.py
```

The per-document move executor still needs runtime effects wiring. Other Go index engines/bindings, image/file and graph adapters, complete Wiki reconciliation, retained task admission, Redis progress, public move endpoints and the move worker remain unfinished. SQL error codes and rollback are checked here; public error envelopes and exact database diagnostic text need endpoint-level acceptance. HTTP/task coverage and verified-entry counts do not change. The preceding 9,476-test frozen execution snapshot excludes these later SQL changes; no full build or packaged acceptance is claimed for this increment.
