//go:build ignore

// Actual Go ES8 physical index moves; only unique indices on an owned loopback engine are mutated.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"

	esrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/elasticsearch/v8"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/elastic/go-elasticsearch/v8"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func main() {
	address := os.Getenv("WEKNORA_ES_PARITY_URL")
	if !strings.HasPrefix(address, "http://127.0.0.1:") {
		panic("owned loopback ES required")
	}
	if len(os.Args) != 2 {
		panic("output path required")
	}
	ctx := context.Background()
	output := []any{}
	request := func(method, path string, body any) map[string]any {
		var data []byte
		if body != nil {
			data, _ = json.Marshal(body)
		}
		req, err := http.NewRequest(method, address+path, bytes.NewReader(data))
		must(err)
		req.Header.Set("Content-Type", "application/json")
		response, err := http.DefaultClient.Do(req)
		must(err)
		defer response.Body.Close()
		out := map[string]any{}
		must(json.NewDecoder(response.Body).Decode(&out))
		if response.StatusCode >= 300 {
			panic(fmt.Sprint(response.StatusCode, out))
		}
		return out
	}
	for _, mapping := range []string{"keyword", "text"} {
		for _, name := range []string{"ordinary", "nil-chunks", "wrong-chunks", "wrong-dimension", "wrong-type", "same-kb", "empty-source", "empty-target", "empty-document", "quoted-scope", "not-found"} {
			func() {
				index := fmt.Sprintf("weknora-go-index-move-%d", time.Now().UnixNano())
				properties := map[string]any{"content": map[string]any{"type": "text"}, "embedding": map[string]any{"type": "dense_vector", "dims": 2, "index": true, "similarity": "cosine"}}
				for _, field := range []string{"chunk_id", "source_id", "knowledge_id", "knowledge_base_id", "tag_id"} {
					p := map[string]any{"type": mapping}
					if mapping == "text" {
						p["fields"] = map[string]any{"keyword": map[string]any{"type": "keyword"}}
					}
					properties[field] = p
				}
				create := map[string]any{"settings": map[string]any{"number_of_shards": 1, "number_of_replicas": 0}, "mappings": map[string]any{"properties": properties}}
				request("PUT", "/"+index, create)
				defer request("DELETE", "/"+index, nil)
				client, err := elasticsearch.NewTypedClient(elasticsearch.Config{Addresses: []string{address}})
				must(err)
				repo := esrepo.NewElasticsearchEngineRepository(client, &config.Config{}, &types.IndexConfig{IndexName: index})
				from, to, doc := "source", "target", "doc"
				chunks := []string{"a", "b"}
				dimension := 2
				kind := "document"
				switch name {
				case "nil-chunks":
					chunks = nil
				case "wrong-chunks":
					chunks = []string{"missing"}
				case "wrong-dimension":
					dimension = -100
				case "wrong-type":
					kind = "faq"
				case "same-kb":
					to = from
				case "empty-source":
					from = ""
				case "empty-target":
					to = ""
				case "empty-document":
					doc = ""
				case "quoted-scope":
					from = " 源\"\\\n"
					to = "<目标>&\"\\\n"
					doc = " 文档\"\\\n"
				case "not-found":
					doc = "missing"
				}
				seedDoc := doc
				if name == "not-found" {
					seedDoc = "doc"
				}
				entries := []*types.IndexInfo{
					{SourceID: "a", SourceType: types.ChunkSourceType, ChunkID: "a", KnowledgeID: seedDoc, KnowledgeBaseID: from, TagID: "old-a", Content: "refund alpha", IsEnabled: true},
					{SourceID: "b", SourceType: types.ChunkSourceType, ChunkID: "b", KnowledgeID: seedDoc, KnowledgeBaseID: from, TagID: "old-b", Content: "refund beta disabled", IsEnabled: false},
					{SourceID: "question", SourceType: types.ChunkSourceType, ChunkID: "a", KnowledgeID: seedDoc, KnowledgeBaseID: from, TagID: "old-question", Content: "refund question", IsEnabled: true},
					{SourceID: "other-doc", SourceType: types.ChunkSourceType, ChunkID: "other-doc", KnowledgeID: "other", KnowledgeBaseID: from, TagID: "untouched", Content: "refund other document", IsEnabled: true},
					{SourceID: "other-kb", SourceType: types.ChunkSourceType, ChunkID: "other-kb", KnowledgeID: seedDoc, KnowledgeBaseID: "third", TagID: "untouched", Content: "refund other kb", IsEnabled: true},
					{SourceID: "already-target", SourceType: types.ChunkSourceType, ChunkID: "already-target", KnowledgeID: seedDoc, KnowledgeBaseID: to, TagID: "target-tag", Content: "refund destination", IsEnabled: true},
				}
				vectors := map[string][]float32{}
				for i, e := range entries {
					vectors[e.SourceID] = []float32{1, float32(i+1) / 10}
				}
				must(repo.BatchSave(ctx, entries, map[string]any{"embedding": vectors}))
				request("POST", "/"+index+"/_refresh", nil)
				snapshot := func() map[string]any {
					search := request("POST", "/"+index+"/_search", map[string]any{"size": 100, "query": map[string]any{"match_all": map[string]any{}}})
					hits := search["hits"].(map[string]any)["hits"].([]any)
					rows := []any{}
					ids := map[string]string{}
					for _, value := range hits {
						hit := value.(map[string]any)
						source := hit["_source"].(map[string]any)
						ids[source["source_id"].(string)] = hit["_id"].(string)
						rows = append(rows, source)
					}
					sort.Slice(rows, func(i, j int) bool {
						return rows[i].(map[string]any)["source_id"].(string) < rows[j].(map[string]any)["source_id"].(string)
					})
					queries := []any{}
					for _, kb := range []string{from, to, "third"} {
						for _, kind := range []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType} {
							q := types.RetrieveParams{Query: "refund", Embedding: []float32{1, .1}, KnowledgeBaseIDs: []string{kb}, TopK: 100, RetrieverType: kind}
							result, err := repo.Retrieve(ctx, q)
							must(err)
							sources := []string{}
							for _, block := range result {
								for _, hit := range block.Results {
									sources = append(sources, hit.SourceID)
								}
							}
							sort.Strings(sources)
							queries = append(queries, map[string]any{"kb": kb, "kind": kind, "sources": sources})
						}
					}
					return map[string]any{"rows": rows, "physical_ids": ids, "queries": queries}
				}
				stages := []any{snapshot()}
				errors := []string{}
				for attempt := 0; attempt < 2; attempt++ {
					err := repo.(interfaces.KnowledgeIndexMover).MoveKnowledgeIndices(ctx, from, to, doc, chunks, dimension, kind)
					message := ""
					if err != nil {
						message = err.Error()
					}
					errors = append(errors, message)
					stages = append(stages, snapshot())
				}
				output = append(output, map[string]any{"name": name, "mapping": mapping, "source": from, "target": to, "knowledge": doc, "chunks": chunks, "dimension": dimension, "kind": kind, "create": create, "entries": entries, "vectors": vectors, "stages": stages, "errors": errors})
			}()
		}
	}
	data, err := json.MarshalIndent(output, "", "  ")
	must(err)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
	fmt.Printf("Exported %d real Go ES8 physical move cases\n", len(output))
}
