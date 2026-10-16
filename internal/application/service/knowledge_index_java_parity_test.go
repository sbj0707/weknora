package service

import (
	"compress/gzip"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

// ExportJavaIndexStateFixtures executes production Go state logic. Run only when
// explicitly regenerating the cross-language corpus; ordinary Go tests skip it.
func TestExportJavaIndexStateFixtures(t *testing.T) {
	path := os.Getenv("WEKNORA_JAVA_INDEX_STATE_FIXTURE")
	if path == "" {
		t.Skip("explicit Java fixture output not requested")
	}
	var cases []map[string]any
	now := time.Date(2026, 10, 14, 1, 2, 3, 0, time.UTC)
	for _, kind := range []string{"vector", "keywords", "graph", "wiki", "questions"} {
		name := map[string]string{"vector": "embedding", "keywords": "embedding", "graph": "postprocess.graph.chunk[0]", "wiki": "postprocess.wiki", "questions": "postprocess.question.batch[0]"}[kind]
		for _, enabled := range []bool{false, true} {
			for _, count := range []*int64{nil, indexCount(0), indexCount(3)} {
				for _, state := range []string{"done", "failed", "running", "cancelled", "skipped", "pending"} {
					for _, parse := range []string{"completed", "finalizing", "cancelled", "failed"} {
						row := types.KnowledgeIndexStatusRow{Kind: kind, Enabled: enabled, Count: count, IndexedCount: indexCount(1)}
						span := types.KnowledgeProcessingSpan{Name: name, Status: state, ID: 1, UpdatedAt: now, FinishedAt: &now, ErrorMessage: "model failed", Input: types.JSONMap{"vector_enabled": true, "keyword_enabled": true}, Output: types.JSONMap{"vectors_written": 2}}
						spans := []types.KnowledgeProcessingSpan{span}
						if kind == "graph" || kind == "questions" {
							spans = append(spans, types.KnowledgeProcessingSpan{Name: "postprocess", Status: "done", Output: types.JSONMap{"planned_graph_count": 2, "enqueued_graph_count": 1, "planned_question_count": 2, "enqueued_question_count": 1}})
						}
						var input any
						raw, _ := json.Marshal(row)
						require.NoError(t, json.Unmarshal(raw, &input))
						spanInputs := []map[string]any{}
						for _, s := range spans {
							b, e := json.Marshal(s)
							require.NoError(t, e)
							var v map[string]any
							require.NoError(t, json.Unmarshal(b, &v))
							v["id"] = s.ID
							spanInputs = append(spanInputs, v)
						}
						resolveDocumentIndexState(&row, parse, spans)
						cases = append(cases, map[string]any{"type": "document", "row": input, "parse_status": parse, "spans": spanInputs, "expected": row})
					}
				}
			}
		}
	}
	for _, kind := range []string{"vector", "keywords", "graph", "wiki", "questions"} {
		for _, enabled := range []bool{false, true} {
			for _, available := range []bool{false, true} {
				for _, status := range []string{"ready", "processing", "failed", ""} {
					for _, chunkType := range []types.ChunkType{types.ChunkTypeText, types.ChunkTypeParentText, types.ChunkTypeImageOCR} {
						row := types.ChunkIndexDetail{Kind: kind, Enabled: enabled, Available: available, Stores: []types.RetrieverEngineType{types.SQLiteRetrieverEngineType, types.PostgresRetrieverEngineType}, Records: []types.ChunkRetrievalRecord{{StoreType: types.SQLiteRetrieverEngineType, SourceID: "chunk"}}, Questions: []types.ChunkQuestionDetail{{ID: "q", Current: true, KeywordIndexed: new(bool)}}}
						chunk := types.Chunk{ID: "chunk", IndexStatus: status, ChunkType: chunkType}
						var input any
						raw, _ := json.Marshal(row)
						require.NoError(t, json.Unmarshal(raw, &input))
						resolveChunkIndexDetail(&row, &chunk, false)
						cases = append(cases, map[string]any{"type": "chunk", "row": input, "chunk": chunk, "refreshing": false, "expected": row})
					}
				}
			}
		}
	}
	// Retry replacement and the done question fan-out container are distinct cases.
	for _, spans := range [][]types.KnowledgeProcessingSpan{
		{{Name: "postprocess.question", Status: "done", Input: types.JSONMap{"batch_count": 2}}},
		{{ID: 1, Name: "embedding", Status: "failed", UpdatedAt: now}, {ID: 2, Name: "embedding", Status: "done", UpdatedAt: now, FinishedAt: &now}},
		{{Name: "postprocess.wiki", Status: "done", Output: types.JSONMap{"pages_dropped": 1}}},
		{{Name: "multimodal", Status: "done"}},
	} {
		kind := "vector"
		if spans[0].Name == "postprocess.question" {
			kind = "questions"
		}
		if spans[0].Name == "postprocess.wiki" {
			kind = "wiki"
		}
		row := types.KnowledgeIndexStatusRow{Kind: kind, Enabled: true, Count: indexCount(0), IndexedCount: indexCount(0)}
		var input any
		raw, _ := json.Marshal(row)
		require.NoError(t, json.Unmarshal(raw, &input))
		var inputs []map[string]any
		for _, span := range spans {
			raw, _ := json.Marshal(span)
			var v map[string]any
			require.NoError(t, json.Unmarshal(raw, &v))
			v["id"] = span.ID
			inputs = append(inputs, v)
		}
		resolveDocumentIndexState(&row, "completed", spans)
		cases = append(cases, map[string]any{"type": "document", "row": input, "parse_status": "completed", "spans": inputs, "expected": row})
	}
	f, err := os.Create(path)
	require.NoError(t, err)
	writer := gzip.NewWriter(f)
	require.NoError(t, json.NewEncoder(writer).Encode(cases))
	require.NoError(t, writer.Close())
	require.NoError(t, f.Close())
	t.Logf("exported %d production Go state cases", len(cases))
}
