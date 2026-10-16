//go:build ignore

// Real public handler + role/KB/API-key middleware + SQLite vec/FTS + model HTTP.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	rpg "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	rsqlite "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	handler "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type scene struct{ name, method, query, body string }

func main() {
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	pgMode := os.Getenv("WEKNORA_HYBRID_PARITY_PG_DSN") != ""
	driver := "sqlite"
	if pgMode {
		driver = "postgres"
	}
	must(os.Setenv("RETRIEVE_DRIVER", driver))
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	utils.ResetSSRFWhitelistForTest()
	vec.Auto()
	scenes := []scene{{"multi-owner-store-deadline", "POST", "", `{"query_text":"refund policy","disable_keywords_match":true,"knowledge_base_ids":["kb","kb-second"]}`}, {"multi-owner-parent-deadline", "POST", "", `{"query_text":"refund policy","disable_keywords_match":true,"knowledge_base_ids":["kb","kb-second"]}`}, {"multi-owner-parent-cancel", "POST", "", `{"query_text":"refund policy","disable_keywords_match":true,"knowledge_base_ids":["kb","kb-second"]}`}}
	scenes = append(scenes, scene{"multi-owner-store-cap", "POST", "", `{"query_text":"refund policy","disable_keywords_match":true,"knowledge_base_ids":["kb","kb-second","kb-owner-3","kb-owner-4","kb-owner-5","kb-owner-6"]}`})
	scenes = append(scenes, scene{"single-owner-parent-deadline", "POST", "", `{"query_text":"refund policy","disable_keywords_match":true}`}, scene{"single-owner-parent-cancel", "POST", "", `{"query_text":"refund policy","disable_keywords_match":true}`})
	out := []any{}
	for _, s := range scenes {
		folder, e := os.MkdirTemp("", "weknora-go-public-hybrid-")
		must(e)
		var dialect gorm.Dialector = sqlite.Open(filepath.Join(folder, "db.sqlite"))
		if pgMode {
			dialect = postgres.Open(os.Getenv("WEKNORA_HYBRID_PARITY_PG_DSN"))
		}
		db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(8)
		if pgMode {
			var database string
			must(db.Raw("SELECT current_database()").Scan(&database).Error)
			if database != "weknora_hybrid_parity" {
				panic("only owned weknora_hybrid_parity accepted")
			}
			must(db.Exec("TRUNCATE chunk_images,embeddings,tenants,knowledge_bases,knowledges,chunks,models,organizations,organization_tenant_members,kb_shares,custom_agents,agent_shares RESTART IDENTITY CASCADE").Error)
		}
		exec := func(query string, args ...any) *gorm.DB {
			if pgMode {
				query = strings.ReplaceAll(query, " AS BLOB", " AS JSONB")
				query = strings.ReplaceAll(query, "is_enabled=0", "is_enabled=false")
			}
			return db.Exec(query, args...)
		}
		if !pgMode {
			for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
				b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
				must(e)
				must(db.Exec(string(b)).Error)
			}
		}
		owner := uint64(1)
		shared := strings.HasPrefix(s.name, "shared") || s.name == "agent"
		if shared {
			owner = 2
		}
		ctx := types.WithCaller(context.WithValue(context.Background(), types.TenantIDContextKey, owner), types.Caller{TenantID: 1, UserID: "caller", Role: types.TenantRoleViewer})
		br, kr, cr, mr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db), repo.NewModelRepository(db)
		must(exec("INSERT INTO tenants(id,name,business) VALUES(1,'caller','fixture'),(2,'owner','fixture')").Error)
		if shared {
			must(exec("INSERT INTO organizations(id,name,owner_id,owner_tenant_id) VALUES('org','Org','owner',2)").Error)
			for _, t := range []int{1, 2} {
				must(exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES(?,?,?,'viewer')", fmt.Sprint(t), "org", t).Error)
			}
		}
		if s.name == "agent" {
			must(repo.NewCustomAgentRepository(db).CreateAgent(ctx, &types.CustomAgent{ID: "agent", TenantID: 2, Name: "Shared", Config: types.CustomAgentConfig{KBSelectionMode: "selected", KnowledgeBases: []string{"kb"}}}))
			must(exec("INSERT INTO agent_shares(id,agent_id,organization_id,source_tenant_id,shared_by_user_id) VALUES('as','agent','org',2,'owner')").Error)
			s.query = "?agent_id=agent"
		}
		strategy := types.DefaultIndexingStrategy()
		if s.name == "graph-only" {
			strategy = types.IndexingStrategy{GraphEnabled: true}
		}
		if s.name == "vector-disabled" {
			strategy.VectorEnabled = false
		}
		if s.name == "keyword-disabled" {
			strategy.KeywordEnabled = false
		}
		kbType := types.KnowledgeBaseTypeDocument
		if strings.HasPrefix(s.name, "faq") {
			kbType = types.KnowledgeBaseTypeFAQ
		}
		must(br.CreateKnowledgeBase(ctx, &types.KnowledgeBase{ID: "kb", TenantID: owner, Name: "KB", Type: kbType, EmbeddingModelID: "embed", IndexingStrategy: strategy}))
		if shared {
			if s.name != "agent" {
				must(exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,shared_by_user_id,source_tenant_id,permission) VALUES('share','kb','org','owner',2,'viewer')").Error)
			}
			if s.name == "shared-revoked" {
				must(exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
			}
			if s.name == "shared-source-left" {
				must(exec("DELETE FROM organization_tenant_members WHERE tenant_id=2").Error)
			}
		}
		for _, k := range []struct {
			id    string
			owner uint64
			model string
		}{{"kb-second", owner, "embed"}, {"kb-private", 9, "embed"}, {"kb-incompatible", owner, "embed-incompatible"}} {
			must(br.CreateKnowledgeBase(ctx, &types.KnowledgeBase{ID: k.id, TenantID: k.owner, Name: k.id, Type: types.KnowledgeBaseTypeDocument, EmbeddingModelID: k.model, IndexingStrategy: types.DefaultIndexingStrategy()}))
		}
		if strings.HasPrefix(s.name, "multi-owner-") {
			must(exec("UPDATE knowledge_bases SET tenant_id=2,embedding_model_id='embed2' WHERE id='kb-second'").Error)
			must(exec("INSERT INTO organizations(id,name,owner_id,owner_tenant_id) VALUES('org','Org','owner',2)").Error)
			for _, t := range []int{1, 2} {
				must(exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES(?,?,?,'viewer')", fmt.Sprint(t), "org", t).Error)
			}
			must(exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,shared_by_user_id,source_tenant_id,permission) VALUES('share','kb-second','org','owner',2,'viewer')").Error)
		}

		for _, id := range []string{"doc-a", "doc-b"} {
			must(kr.CreateKnowledge(ctx, &types.Knowledge{ID: id, TenantID: owner, KnowledgeBaseID: "kb", Title: "Document " + id, Type: "file", FileName: "source.pdf", Source: "manual", Channel: "web", Description: "Document description"}))
		}
		must(kr.CreateKnowledge(ctx, &types.Knowledge{ID: "doc-second", TenantID: owner, KnowledgeBaseID: "kb-second", Title: "Second", Type: "file"}))
		entries := []*types.IndexInfo{}
		vectors := map[string][]float32{"alpha": {1, 0}, "beta": {.8, .6}, "china": {.2, .98}, "second": {.7, .7}}
		for i, c := range []struct{ id, doc, kb, content, tag string }{{"alpha", "doc-a", "kb", "alpha refund policy shared words", "tag-a"}, {"beta", "doc-b", "kb", "beta refund policy other words additional", "tag-b"}, {"china", "doc-b", "kb", "中国退款政策 长江大桥", ""}, {"second", "doc-second", "kb-second", "second refund policy shared", ""}} {
			kind := types.ChunkTypeText
			if strings.HasPrefix(s.name, "faq") {
				kind = types.ChunkTypeFAQ
			}
			must(cr.CreateChunks(ctx, []*types.Chunk{{ID: c.id, TenantID: owner, KnowledgeID: c.doc, KnowledgeBaseID: c.kb, Content: c.content, ChunkType: kind, TagID: c.tag, ChunkIndex: i, StartAt: 3, EndAt: 33, IsEnabled: true}}))
			entries = append(entries, &types.IndexInfo{SourceID: c.id, ChunkID: c.id, KnowledgeID: c.doc, KnowledgeBaseID: c.kb, Content: c.content, TagID: c.tag, IsEnabled: true})
		}
		if strings.HasPrefix(s.name, "multi-owner-") {
			must(exec("UPDATE knowledges SET tenant_id=2 WHERE id='doc-second'").Error)
			must(exec("UPDATE chunks SET tenant_id=2 WHERE id='second'").Error)
		}

		if s.name == "disabled-chunk" {
			must(exec("UPDATE chunks SET is_enabled=0 WHERE id='alpha'").Error)
		}
		if s.name == "processing-chunk" || s.name == "failed-chunk" {
			state := "processing"
			if s.name == "failed-chunk" {
				state = "failed"
			}
			must(exec("UPDATE chunks SET index_status=? WHERE id='alpha'", state).Error)
		}
		if s.name == "deleted-document" {
			must(exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc-a'").Error)
		}
		if s.name == "foreign-chunk-owner" {
			must(exec("UPDATE chunks SET tenant_id=9 WHERE id='alpha'").Error)
		}
		if s.name == "foreign-chunk-kb" {
			must(exec("UPDATE chunks SET knowledge_base_id='kb-private' WHERE id='alpha'").Error)
		}
		if strings.HasPrefix(s.name, "rerank-metadata-") {
			metadata := `{"generated_questions":[{"question":false,"question":"Question?"}]}`
			if s.name == "rerank-metadata-null-duplicate" {
				metadata = `{"generated_questions":[{"question":"Question?","question":null}]}`
			}
			must(exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id='alpha'", metadata).Error)
		}
		if s.name == "metadata" {
			must(exec("UPDATE knowledges SET metadata=CAST(? AS BLOB),custom_metadata=CAST(? AS BLOB) WHERE id='doc-a'", `{"z":1e20,"a":[1,true,null]}`, `{"note":"manual context"}`).Error)
			must(exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id='alpha'", `{"generated_questions":[{"id":"q","question":"Refund?"}]}`).Error)
		}
		if s.name == "source-locators" {
			must(exec("UPDATE chunks SET source_locators=CAST(? AS BLOB) WHERE id='alpha'", `[{"type":"text","start":3,"end":33}]`).Error)
		}
		if s.name == "parent" || s.name == "nearby" || s.name == "relation" || s.name == "image" {
			must(cr.CreateChunks(ctx, []*types.Chunk{{ID: "extra", TenantID: owner, KnowledgeBaseID: "kb", KnowledgeID: "doc-a", Content: "Additional context", ChunkType: types.ChunkTypeText, IsEnabled: true}}))
			if s.name == "parent" {
				must(exec("UPDATE chunks SET parent_chunk_id='extra' WHERE id='alpha'").Error)
			}
			if s.name == "nearby" {
				must(exec("UPDATE chunks SET next_chunk_id='extra' WHERE id='alpha'").Error)
			}
			if s.name == "relation" {
				must(exec("UPDATE chunks SET relation_chunks=CAST('[\"extra\"]' AS BLOB) WHERE id='alpha'").Error)
			}
			if s.name == "image" {
				must(exec("UPDATE chunks SET parent_chunk_id='alpha',chunk_type='image_caption',image_info=CAST(? AS BLOB) WHERE id='extra'", `[{"url":"https://image.invalid/a","caption":"Refund diagram"}]`).Error)
			}
		}
		if strings.HasPrefix(s.name, "context-order-") {
			for _, id := range []string{"context-a", "context-b", "context-c"} {
				must(cr.CreateChunks(ctx, []*types.Chunk{{ID: id, TenantID: owner, KnowledgeBaseID: "kb", KnowledgeID: "doc-a", Content: "Additional " + id, ChunkType: types.ChunkTypeText, IsEnabled: true}}))
			}
			must(exec("UPDATE chunks SET relation_chunks=CAST('[\"context-c\",\"context-a\",\"context-b\"]' AS BLOB) WHERE id='alpha'").Error)
		}
		if s.name == "parent-foreign-doc" || s.name == "relation-foreign-kb" || s.name == "nearby-foreign-owner" || s.name == "image-foreign-doc" {
			extra := &types.Chunk{ID: "foreign-extra", TenantID: owner, KnowledgeBaseID: "kb", KnowledgeID: "doc-b", Content: "Foreign document must never be expanded", ChunkType: types.ChunkTypeText, IsEnabled: true}
			if s.name == "relation-foreign-kb" {
				extra.KnowledgeBaseID = "kb-second"
				extra.KnowledgeID = "doc-second"
			}
			if s.name == "nearby-foreign-owner" {
				extra.TenantID = 9
				extra.KnowledgeID = "doc-a"
			}
			if s.name == "image-foreign-doc" {
				extra.ParentChunkID = "alpha"
				extra.ChunkType = types.ChunkTypeImageCaption
				extra.ImageInfo = `[{"url":"https://foreign.invalid/image","caption":"Foreign caption"}]`
			}
			must(cr.CreateChunks(ctx, []*types.Chunk{extra}))
			if s.name == "parent-foreign-doc" {
				must(exec("UPDATE chunks SET parent_chunk_id='foreign-extra' WHERE id='alpha'").Error)
			}
			if s.name == "relation-foreign-kb" {
				must(exec("UPDATE chunks SET relation_chunks=CAST('[\"foreign-extra\"]' AS BLOB) WHERE id='alpha'").Error)
			}
			if s.name == "nearby-foreign-owner" {
				must(exec("UPDATE chunks SET next_chunk_id='foreign-extra' WHERE id='alpha'").Error)
			}
		}
		if s.name == "faq-invalid-negative" || s.name == "faq-duplicate-negative" {
			must(exec("UPDATE chunks SET chunk_type='faq' WHERE id='alpha'").Error)
			metadata := `{"negative_questions":["refund policy"],"standard_question":false}`
			if s.name == "faq-duplicate-negative" {
				metadata = `{"negative_questions":["refund policy"],"negative_questions":null}`
			}
			must(exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id='alpha'", metadata).Error)
		}
		if s.name == "faq-negative" {
			must(exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id='alpha'", `{"negative_questions":["refund policy"]}`).Error)
		}
		if s.name == "faq-unicode-lower" {
			must(exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id='alpha'", `{"negative_questions":["i"]}`).Error)
		}
		if s.name == "faq-iterative" || s.name == "faq-iterative-negative" || s.name == "faq-round-limit" {
			entries = nil
			vectors = map[string][]float32{}
			total := 70
			if s.name == "faq-round-limit" {
				total = 600
			}
			for i := 0; i < total; i++ {
				id := fmt.Sprintf("alias-%03d", i)
				entries = append(entries, &types.IndexInfo{SourceID: id, ChunkID: "alpha", KnowledgeID: "doc-a", KnowledgeBaseID: "kb", Content: "alpha alias refund", IsEnabled: true})
				vectors[id] = []float32{1, 0}
			}
			for _, c := range []struct {
				id     string
				vector []float32
			}{{"beta", []float32{.8, .6}}, {"china", []float32{.2, .98}}} {
				entries = append(entries, &types.IndexInfo{SourceID: c.id, ChunkID: c.id, KnowledgeID: "doc-b", KnowledgeBaseID: "kb", Content: c.id + " refund", IsEnabled: true})
				vectors[c.id] = c.vector
			}
			if s.name == "faq-iterative-negative" {
				must(exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id='alpha'", `{"negative_questions":["refund policy"]}`).Error)
			}
		}

		if s.name == "multi-owner-store-cap" {
			for t := 3; t <= 6; t++ {
				kb := fmt.Sprintf("kb-owner-%d", t)
				doc := fmt.Sprintf("doc-owner-%d", t)
				chunk := fmt.Sprintf("chunk-owner-%d", t)
				must(exec("INSERT INTO tenants(id,name,business) VALUES(?,?,'fixture')", t, kb).Error)
				must(br.CreateKnowledgeBase(ctx, &types.KnowledgeBase{ID: kb, TenantID: uint64(t), Name: kb, Type: types.KnowledgeBaseTypeDocument, EmbeddingModelID: "embed2", IndexingStrategy: types.DefaultIndexingStrategy()}))
				must(kr.CreateKnowledge(ctx, &types.Knowledge{ID: doc, TenantID: uint64(t), KnowledgeBaseID: kb, Title: doc, Type: "file"}))
				must(cr.CreateChunks(ctx, []*types.Chunk{{ID: chunk, TenantID: uint64(t), KnowledgeID: doc, KnowledgeBaseID: kb, Content: "refund policy", ChunkType: types.ChunkTypeText, IsEnabled: true}}))
				must(exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES(?, 'org',?,'viewer')", kb, t).Error)
				must(exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES(?,?,'org',?,'viewer','owner')", kb, kb, t).Error)
				entries = append(entries, &types.IndexInfo{SourceID: chunk, ChunkID: chunk, KnowledgeID: doc, KnowledgeBaseID: kb, Content: "refund policy", IsEnabled: true})
				vectors[chunk] = []float32{1, .125}
			}
		}
		var index interfaces.RetrieveEngineRepository = rsqlite.NewSQLiteRetrieveEngineRepository(db)
		if pgMode {
			index = rpg.NewPostgresRetrieveEngineRepository(db)
		}
		must(index.BatchSave(ctx, entries, map[string]any{"embedding": vectors}))
		registry := retriever.NewRetrieveEngineRegistry(nil, nil)
		must(registry.Register(retriever.NewKVHybridRetrieveEngine(index, types.RetrieverEngineType(driver))))
		var lock sync.Mutex
		requests := []any{}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var v any
			decoder := json.NewDecoder(r.Body)
			decoder.UseNumber()
			must(decoder.Decode(&v))
			lock.Lock()
			requests = append(requests, map[string]any{"path": r.URL.Path, "body": v})
			lock.Unlock()
			if s.name == "embedding-error" || s.name == "rerank-error" && strings.HasSuffix(r.URL.Path, "rerank") {
				w.WriteHeader(400)
				fmt.Fprint(w, `{"error":{"message":"fixture model rejection"}}`)
				return
			}
			if strings.HasSuffix(r.URL.Path, "embeddings") {
				must(json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"index": 0, "embedding": []float32{1, .125}}}}))
				return
			}
			docs := v.(map[string]any)["documents"].([]any)
			scores := []any{}
			for i := len(docs) - 1; i >= 0; i-- {
				score := .63
				if strings.Contains(docs[i].(string), "beta") {
					score = .88
				} else if strings.Contains(docs[i].(string), "alpha") {
					score = .74
				}
				scores = append(scores, map[string]any{"index": i, "relevance_score": score})
			}
			must(json.NewEncoder(w).Encode(map[string]any{"results": scores}))
		}))
		for _, m := range []struct {
			id    string
			owner uint64
			kind  types.ModelType
			name  string
		}{{"embed", owner, types.ModelTypeEmbedding, "unknown"}, {"embed2", 2, types.ModelTypeEmbedding, "unknown"}, {"embed-incompatible", owner, types.ModelTypeEmbedding, "different"}, {"rerank", 1, types.ModelTypeRerank, "unknown"}, {"foreign-rerank", 9, types.ModelTypeRerank, "unknown"}} {
			if s.name == "no-rerank-model" && m.id == "rerank" {
				continue
			}
			must(mr.Create(ctx, &types.Model{ID: m.id, TenantID: m.owner, Name: m.name, Type: m.kind, Status: types.ModelStatusActive, Source: types.ModelSourceRemote, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", Provider: "generic", APIKey: "synthetic-key", EmbeddingParameters: types.EmbeddingParameters{Dimension: 2}}}))
		}
		if s.name == "multi-owner-store-cap" {
			must(exec("UPDATE models SET is_builtin=true WHERE id='embed2'").Error)
		}
		models := svc.NewModelService(mr, br, nil, nil, nil, nil)
		shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), br, kr, cr, nil)
		agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
		kbs := svc.NewKnowledgeBaseService(br, kr, cr, repo.NewKBShareRepository(db), shares, models, registry, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
		enabled := true
		cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
		h := handler.NewKnowledgeBaseHandler(cfg, kbs, nil, shares, agents, nil, nil, nil, nil, nil, nil)
		tenant := &types.Tenant{ID: 1}
		if s.name == "vector-only-engine" || s.name == "keyword-only-engine" {
			kind := types.VectorRetrieverType
			if s.name == "keyword-only-engine" {
				kind = types.KeywordsRetrieverType
			}
			tenant.RetrieverEngines.Engines = []types.RetrieverEngineParams{{RetrieverEngineType: types.RetrieverEngineType(driver), RetrieverType: kind}}
		}
		if s.name == "rerank-tenant" {
			tenant.RetrievalConfig = &types.RetrievalConfig{RerankModelID: "rerank", RerankThreshold: .8}
		}
		role := types.TenantRoleViewer
		machine := strings.HasPrefix(s.name, "api-") || s.name == "shared-api" || s.name == "resource-restricted" || s.name == "multi-api-scope"
		scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"retrieve"}, KnowledgeBaseIDs: []string{"kb"}}
		if s.name == "multi-api-scope" {
			s.body = `{"query_text":"refund policy","knowledge_base_ids":["kb","kb-second"]}`
		}
		if s.name == "api-chat" {
			scope.Capabilities = types.StringArray{"chat"}
		}
		if s.name == "api-full" {
			scope.FullAccess = true
			scope.Capabilities = nil
			scope.KnowledgeBaseIDs = nil
		}
		if s.name == "api-denied-kb" {
			scope.KnowledgeBaseIDs = []string{"other"}
		}
		must(os.Unsetenv("RESOURCE_URL_MODE"))
		if s.name == "resource-env-invalid" {
			must(os.Setenv("RESOURCE_URL_MODE", "bad"))
		}
		if s.name == "resource-env-public" {
			must(os.Setenv("RESOURCE_URL_MODE", "public"))
		}
		input := map[string]any{}
		blobs := map[string]map[string][]string{}
		for _, table := range []string{"tenants", "knowledge_bases", "knowledges", "chunks", "models", "organizations", "organization_tenant_members", "kb_shares", "custom_agents", "agent_shares"} {
			var rows []map[string]any
			must(db.Table(table).Order("id").Find(&rows).Error)
			for _, row := range rows {
				var cols []string
				for key, value := range row {
					if b, ok := value.([]byte); ok {
						row[key] = string(b)
					}
					var typ string
					if !pgMode {
						must(sql.QueryRow(`SELECT typeof("`+key+`") FROM "`+table+`" WHERE id=?`, row["id"]).Scan(&typ))
					}
					if typ == "blob" {
						cols = append(cols, key)
					}
				}
				if len(cols) > 0 {
					if blobs[table] == nil {
						blobs[table] = map[string][]string{}
					}
					blobs[table][fmt.Sprint(row["id"])] = cols
				}
			}
			input[table] = rows
		}
		r := gin.New()
		r.Use(middleware.ErrorHandler())
		r.Use(func(c *gin.Context) {
			cx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, uint64(1))
			cx = context.WithValue(cx, types.UserIDContextKey, "caller")
			cx = context.WithValue(cx, types.TenantRoleContextKey, role)
			cx = context.WithValue(cx, types.TenantInfoContextKey, tenant)
			if machine {
				cx = types.WithTenantAPIKeyScope(cx, scope)
			}
			c.Request = c.Request.WithContext(cx)
			c.Set(types.TenantIDContextKey.String(), uint64(1))
			c.Set(types.UserIDContextKey.String(), "caller")
			c.Next()
		})
		path := "/api/v1/knowledge-bases/:id/hybrid-search"
		gate := middleware.NewAPIKeyRouteAuthorizer()
		for _, method := range []string{"GET", "POST"} {
			gate.Register(method, path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve))
			r.Handle(method, path, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), middleware.RequireKBAccess(middleware.KBIDFromParam("id"), types.OrgRoleViewer, kbs, shares, agents, cfg), h.HybridSearch)
		}
		request := httptest.NewRequest(s.method, "/api/v1/knowledge-bases/kb/hybrid-search"+s.query, strings.NewReader(s.body))
		request.Header.Set("Content-Type", "application/json")
		if !pgMode {
			panic("blocked oracle requires an owned physical PostgreSQL")
		}
		must(os.Setenv("MULTI_STORE_RETRIEVE_TIMEOUT_SEC", "1"))
		transaction, e := sql.Begin()
		must(e)
		_, e = transaction.Exec("LOCK TABLE embeddings IN ACCESS EXCLUSIVE MODE")
		must(e)
		started := time.Now()
		done := make(chan struct{})
		maxBlocked := 0
		var observe sync.Mutex
		go func() {
			defer close(done)
			for time.Since(started) < 2500*time.Millisecond {
				var n int
				must(sql.QueryRow("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%embeddings%' AND pid<>pg_backend_pid()").Scan(&n))
				observe.Lock()
				if n > maxBlocked {
					maxBlocked = n
				}
				observe.Unlock()
				time.Sleep(10 * time.Millisecond)
			}
		}()
		cx := request.Context()
		cancel := func() {}
		if strings.HasSuffix(s.name, "parent-deadline") {
			cx, cancel = context.WithTimeout(cx, 300*time.Millisecond)
		}
		if strings.HasSuffix(s.name, "parent-cancel") {
			cx, cancel = context.WithCancel(cx)
			time.AfterFunc(300*time.Millisecond, cancel)
		}
		request = request.WithContext(cx)
		response := httptest.NewRecorder()
		r.ServeHTTP(response, request)
		elapsed := time.Since(started)
		cancel()
		must(transaction.Rollback())
		<-done
		if maxBlocked < 1 || !strings.HasPrefix(s.name, "single-owner-") && maxBlocked < 2 || s.name == "multi-owner-store-cap" && maxBlocked != 4 {
			panic(fmt.Sprintf("actual concurrent blocked physical queries not observed: %d", maxBlocked))
		}
		fmt.Println(s.name, "blocked", maxBlocked, "elapsed", elapsed)

		var body any
		decoder := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
		decoder.UseNumber()
		must(decoder.Decode(&body))
		out = append(out, map[string]any{"name": s.name, "method": s.method, "query": s.query, "request": s.body, "status": response.Code, "body": body, "rows": input, "sql_blobs": blobs, "entries": entries, "vectors": vectors, "model_http": requests, "caller": 1, "machine": machine, "scope": scope, "tenant_context": tenant, "resource_default": os.Getenv("RESOURCE_URL_MODE"), "driver": driver, "multi_store_timeout": 1, "blocked_queries": maxBlocked, "elapsed_millis": elapsed.Milliseconds()})
		server.Close()
		must(sql.Close())
		must(os.RemoveAll(folder))
	}
	data, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
	fmt.Println("exported", len(out), "production Go hybrid HTTP/SQLite/model scenarios")
}
