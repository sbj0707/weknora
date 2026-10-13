//go:build ignore

// Replays Go's real model repository -> SQLite BLOB -> model service -> HTTP client.
// Run from repository root: go run backend-java/src/test/java/com/tencent/weknora/model/client/model_blob_fixture.go
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"

	repo "github.com/Tencent/WeKnora/internal/application/repository"
	service "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	glog "gorm.io/gorm/logger"
)

func main() {
	must := func(err error) {
		if err != nil {
			panic(err)
		}
	}
	must(os.Setenv("SYSTEM_AES_KEY", "")) // Deterministic synthetic fixture credentials only.
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SSRF_WHITELIST_EXTRA", ""))
	utils.ResetSSRFWhitelistForTest()
	dir, err := os.MkdirTemp("", "weknora-model-blob-go-")
	must(err)
	defer os.RemoveAll(dir)
	db, err := gorm.Open(sqlite.Open(filepath.Join(dir, "db.sqlite")), &gorm.Config{Logger: glog.Default.LogMode(glog.Silent)})
	must(err)
	sqlDB, err := db.DB()
	must(err)
	defer sqlDB.Close()
	schema, err := os.ReadFile("migrations/sqlite/000000_init.up.sql")
	must(err)
	must(db.Exec(string(schema)).Error)
	must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(7,'caller','fixture'),(8,'owner','fixture')").Error)
	var mu sync.Mutex
	requests := []any{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		must(json.NewDecoder(r.Body).Decode(&body))
		headers := map[string]string{}
		for _, key := range []string{"Authorization", "api-key", "x-api-key", "anthropic-version", "anthropic-beta", "X-Test", "Accept", "session_id", "x-client-request-id", "x-session-affinity"} {
			if value := r.Header.Get(key); value != "" {
				headers[http.CanonicalHeaderKey(key)] = value
			}
		}
		// Capture the same stable header projection used by Java's native client harness.
		lower := map[string]string{}
		for key, value := range headers {
			lower[strings.ToLower(key)] = value
		}
		mu.Lock()
		requests = append(requests, map[string]any{"path": r.URL.Path, "query": r.URL.RawQuery, "body": body, "headers": lower})
		mu.Unlock()
		inputs := body["input"].([]any)
		data := []any{}
		for i := len(inputs) - 1; i >= 0; i-- {
			data = append(data, map[string]any{"index": i, "embedding": []float32{float32(len(inputs[i].(string))), .12345678}})
		}
		w.Header().Set("Content-Type", "application/json")
		must(json.NewEncoder(w).Encode(map[string]any{"data": data}))
	}))
	defer server.Close()
	modelRepo := repo.NewModelRepository(db)
	models := service.NewModelService(modelRepo, repo.NewKnowledgeBaseRepository(db), nil, nil, nil, nil)
	cases := []any{}
	for _, owner := range []uint64{7, 8} {
		id := fmt.Sprintf("blob-%d", owner)
		model := &types.Model{ID: id, TenantID: owner, Name: "unknown", Type: types.ModelTypeEmbedding, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-api-key", Provider: "generic", EmbeddingParameters: types.EmbeddingParameters{Dimension: 2}}}
		must(modelRepo.Create(context.Background(), model))
		var storageType string
		var parameters []byte
		must(db.Raw("SELECT typeof(parameters) FROM models WHERE id=?", id).Scan(&storageType).Error)
		must(sqlDB.QueryRow("SELECT parameters FROM models WHERE id=?", id).Scan(&parameters))
		if storageType != "blob" {
			panic("production model repository did not persist BLOB")
		}
		var params map[string]any
		must(json.Unmarshal(parameters, &params))
		params["base_url"] = "{base}/v1"
		ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(7))
		for _, query := range []string{"a", "three", "中😀"} {
			requests = []any{}
			embedder, err := models.GetEmbeddingModelForTenant(ctx, id, owner)
			must(err)
			result, err := embedder.Embed(types.WithEmbedQuery(ctx), query)
			must(err)
			cases = append(cases, map[string]any{"owner": owner, "id": id, "sqlite_type": storageType, "parameters": params, "query": query, "result": result, "requests": requests})
		}
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	must(encoder.Encode(cases))
}
