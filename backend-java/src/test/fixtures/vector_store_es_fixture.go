//go:build ignore

// Actual production Go ES8 repository against an owned, loopback-only engine.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	esrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/elasticsearch/v8"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/elastic/go-elasticsearch/v8"
	"net/http"
	"os"
	"strings"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func main() {
	address := os.Getenv("WEKNORA_ES_PARITY_URL")
	if !strings.HasPrefix(address, "http://127.0.0.1:") {
		panic("only owned loopback ES allowed")
	}
	ctx := context.Background()
	output := []any{}
	request := func(method, path string, value any) map[string]any {
		var data []byte
		if value != nil {
			data, _ = json.Marshal(value)
		}
		req, e := http.NewRequest(method, address+path, bytes.NewReader(data))
		must(e)
		req.Header.Set("Content-Type", "application/json")
		res, e := http.DefaultClient.Do(req)
		must(e)
		defer res.Body.Close()
		var out map[string]any
		must(json.NewDecoder(res.Body).Decode(&out))
		if res.StatusCode >= 300 {
			panic(fmt.Sprint(res.StatusCode, out))
		}
		return out
	}
	for _, mapping := range []string{"keyword", "text"} {
		index := fmt.Sprintf("weknora-oracle-%d-%s", time.Now().UnixNano(), mapping)
		props := map[string]any{"content": map[string]any{"type": "text"}, "embedding": map[string]any{"type": "dense_vector", "dims": 2, "index": true, "similarity": "cosine"}}
		for _, field := range []string{"chunk_id", "source_id", "knowledge_id", "knowledge_base_id", "tag_id"} {
			m := map[string]any{"type": mapping}
			if mapping == "text" {
				m["fields"] = map[string]any{"keyword": map[string]any{"type": "keyword"}}
			}
			props[field] = m
		}
		request("PUT", "/"+index, map[string]any{"settings": map[string]any{"number_of_shards": 1, "number_of_replicas": 0}, "mappings": map[string]any{"properties": props}})
		client, e := elasticsearch.NewTypedClient(elasticsearch.Config{Addresses: []string{address}})
		must(e)
		repo := esrepo.NewElasticsearchEngineRepository(client, &config.Config{}, &types.IndexConfig{IndexName: index, NumberOfShards: 1})
		entries := []*types.IndexInfo{{SourceID: "a", ChunkID: "a", KnowledgeID: "doc-a", KnowledgeBaseID: "kb", TagID: "tag-a", Content: "refund policy alpha", IsEnabled: true}, {SourceID: "b", ChunkID: "b", KnowledgeID: "doc-b", KnowledgeBaseID: "kb", TagID: "tag-b", Content: "refund policy beta words additional text", IsEnabled: true}, {SourceID: "disabled", ChunkID: "disabled", KnowledgeID: "doc-a", KnowledgeBaseID: "kb", Content: "refund disabled", IsEnabled: false}, {SourceID: "foreign", ChunkID: "foreign", KnowledgeID: "foreign-doc", KnowledgeBaseID: "foreign-kb", Content: "refund foreign", IsEnabled: true}}
		vectors := map[string][]float32{"a": {1, 0}, "b": {.8, .6}, "disabled": {.2, .98}, "foreign": {.7, .7}}
		must(repo.BatchSave(ctx, entries, map[string]any{"embedding": vectors}))
		request("POST", "/"+index+"/_refresh", nil)
		queries := []types.RetrieveParams{}
		base := types.RetrieveParams{Query: "refund policy", Embedding: []float32{1, .125}, KnowledgeBaseIDs: []string{"kb"}, TopK: 8, RetrieverType: types.VectorRetrieverType}
		for _, kind := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
			q := base
			q.RetrieverType = kind
			queries = append(queries, q)
			q.TopK = 1
			queries = append(queries, q)
			q = base
			q.RetrieverType = kind
			q.KnowledgeIDs = []string{"doc-b"}
			queries = append(queries, q)
			q = base
			q.RetrieverType = kind
			q.TagIDs = []string{"tag-a"}
			queries = append(queries, q)
			q = base
			q.RetrieverType = kind
			q.KnowledgeIDs = []string{"foreign-doc"}
			queries = append(queries, q)
			q = base
			q.RetrieverType = kind
			q.Threshold = .9999
			queries = append(queries, q)
		}
		results := []any{}
		for _, q := range queries {
			r, e := repo.Retrieve(ctx, q)
			must(e)
			for _, block := range r {
				for _, hit := range block.Results {
					if hit.ID == "" {
						panic("actual ID absent")
					}
					hit.ID = "<engine-generated>"
				}
			}
			results = append(results, map[string]any{"query": q, "results": r})
		}
		counts := []any{request("GET", "/"+index+"/_count", nil)["count"]}
		for _, field := range []string{"chunk_id", "source_id", "knowledge_id"} {
			switch field {
			case "chunk_id":
				must(repo.DeleteByChunkIDList(ctx, []string{"a"}, 2, "document"))
			case "source_id":
				must(repo.DeleteBySourceIDList(ctx, []string{"b"}, 2, "document"))
			case "knowledge_id":
				must(repo.DeleteByKnowledgeIDList(ctx, []string{"doc-a"}, 2, "document"))
			}
			request("POST", "/"+index+"/_refresh", nil)
			counts = append(counts, request("GET", "/"+index+"/_count", nil)["count"])
		}
		output = append(output, map[string]any{"mapping": mapping, "entries": entries, "vectors": vectors, "queries": results, "delete_counts": counts, "estimated_size": repo.EstimateStorageSize(ctx, entries, map[string]any{"embedding": vectors})})
		request("DELETE", "/"+index, nil)
	}
	data, e := json.Marshal(output)
	must(e)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
	fmt.Println("exported production Go ES8 physical groups", len(output))
}
