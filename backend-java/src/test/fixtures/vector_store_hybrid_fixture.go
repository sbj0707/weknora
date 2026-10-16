//go:build ignore

// Real public handler + role/KB/API-key middleware + SQLite vec/FTS + model HTTP.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	res "github.com/Tencent/WeKnora/internal/application/repository/retriever/elasticsearch/v8"
	rpg "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	rsqlite "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/container"
	handler "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	vec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/elastic/go-elasticsearch/v8"
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
	scenes := []scene{}
	add := func(name, body string) { scenes = append(scenes, scene{name, "POST", "", body}) }
	for _, s := range []scene{{"default", "POST", "", `{"query_text":"refund policy"}`}, {"get-body", "GET", "", `{"query_text":"refund policy"}`}, {"get-query-only", "GET", "?query_text=refund", ""}} {
		scenes = append(scenes, s)
	}
	for _, p := range []struct{ name, body string }{
		{"empty", ""}, {"incomplete", "{"}, {"null", "null"}, {"array", "[]"}, {"empty-object", "{}"}, {"empty-query", `{"query_text":" \t\u3000"}`}, {"number-query", `{"query_text":1}`}, {"unknown", `{"query_text":"refund","unknown":{"x":1}}`}, {"casefold", `{"QUERY_TEXT":"refund","MATCH_COUNT":1}`}, {"trailing", `{"query_text":"refund"}{"query_text":"ignored"}`}, {"duplicate-null", `{"query_text":"refund","query_text":null}`}, {"duplicate-type", `{"query_text":1,"query_text":"refund"}`},
		{"keyword-only", `{"query_text":"refund policy","disable_vector_match":true}`}, {"vector-only", `{"query_text":"refund policy","disable_keywords_match":true}`}, {"both-disabled", `{"query_text":"refund policy","disable_keywords_match":true,"disable_vector_match":true}`}, {"precomputed", `{"query_embedding":[1,0.125],"disable_keywords_match":true}`}, {"precomputed-with-keywords", `{"query_embedding":[1,0.125]}`}, {"precomputed-disabled", `{"query_embedding":[1,0.125],"disable_keywords_match":true,"disable_vector_match":true}`}, {"precomputed-dimension", `{"query_embedding":[1],"disable_keywords_match":true}`}, {"precomputed-null", `{"query_embedding":[null,1],"disable_keywords_match":true}`}, {"precomputed-overflow", `{"query_embedding":[1e100,1],"disable_keywords_match":true}`},
		{"match-one", `{"query_text":"refund policy","match_count":1}`}, {"match-zero", `{"query_text":"refund policy","match_count":0}`}, {"match-negative", `{"query_text":"refund policy","match_count":-1}`}, {"match-max", `{"query_text":"refund policy","match_count":9223372036854775807}`}, {"match-overflow", `{"query_text":"refund policy","match_count":9223372036854775808}`}, {"match-fraction", `{"query_text":"refund policy","match_count":1.0}`}, {"threshold-high", `{"query_text":"refund policy","vector_threshold":1.1,"keyword_threshold":1e20}`}, {"threshold-negative", `{"query_text":"refund policy","vector_threshold":-1,"keyword_threshold":-1}`},
		{"document-filter", `{"query_text":"refund policy","knowledge_ids":["doc-b"]}`}, {"document-missing", `{"query_text":"refund policy","knowledge_ids":["missing"]}`}, {"document-null", `{"query_text":"refund policy","knowledge_ids":null}`}, {"document-invalid", `{"query_text":"refund policy","knowledge_ids":[1]}`}, {"tag-filter", `{"query_text":"refund policy","tag_ids":["tag-b"]}`}, {"tag-missing", `{"query_text":"refund policy","tag_ids":["missing"]}`}, {"scope-tags", `{"query_text":"refund policy","scope_tag_ids":["missing"]}`}, {"only-recommended", `{"query_text":"refund policy","only_recommended":true}`}, {"skip-enrichment", `{"query_text":"refund policy","skip_context_enrichment":true}`}, {"no-hits", `{"query_text":"neverfound","disable_vector_match":true}`}, {"chinese", `{"query_text":"中国退款","disable_vector_match":true}`},
		{"rerank-default", `{"query_text":"refund policy","rerank":{}}`}, {"rerank-disabled", `{"query_text":"refund policy","rerank":{"enabled":false}}`}, {"rerank-null", `{"query_text":"refund policy","rerank":null}`}, {"rerank-one", `{"query_text":"refund policy","rerank":{"top_k":1}}`}, {"rerank-negative", `{"query_text":"refund policy","rerank":{"top_k":-1}}`}, {"rerank-excess", `{"query_text":"refund policy","rerank":{"top_k":201}}`}, {"rerank-empty-query", `{"query_embedding":[1,0],"disable_keywords_match":true,"rerank":{}}`}, {"rerank-wrong-model", `{"query_text":"refund policy","rerank":{"model_id":"embed"}}`}, {"rerank-missing-model", `{"query_text":"refund policy","rerank":{"model_id":"missing"}}`}, {"rerank-foreign-model", `{"query_text":"refund policy","rerank":{"model_id":"foreign-rerank"}}`}, {"rerank-threshold", `{"query_text":"refund policy","rerank":{"threshold":0.9}}`}, {"rerank-rejected", `{"query_text":"refund policy","rerank":{"threshold":5}}`}, {"rerank-null-fields", `{"query_text":"refund policy","rerank":{"enabled":null,"threshold":null}}`}, {"rerank-type", `{"query_text":"refund policy","rerank":[]}`},
		{"multi-own", `{"query_text":"refund policy","knowledge_base_ids":["kb","kb-second"]}`}, {"multi-missing", `{"query_text":"refund policy","knowledge_base_ids":["kb","missing"]}`}, {"multi-without-primary", `{"query_text":"refund policy","knowledge_base_ids":["kb-second"]}`}, {"multi-foreign", `{"query_text":"refund policy","knowledge_base_ids":["kb","kb-private"]}`}, {"multi-mismatch", `{"query_text":"refund policy","knowledge_base_ids":["kb","kb-incompatible"]}`},
	} {
		add(p.name, p.body)
	}
	for _, n := range []string{"shared", "shared-api", "agent", "shared-revoked", "shared-source-left", "api-retrieve", "api-chat", "api-full", "api-denied-kb", "disabled-chunk", "processing-chunk", "failed-chunk", "deleted-document", "foreign-chunk-owner", "foreign-chunk-kb", "metadata", "source-locators", "parent", "nearby", "relation", "image", "graph-only", "vector-disabled", "keyword-disabled", "faq", "faq-negative", "embedding-error", "rerank-error", "no-rerank-model", "rerank-tenant"} {
		body := `{"query_text":"refund policy"}`
		if strings.Contains(n, "rerank") {
			body = `{"query_text":"refund policy","rerank":{}}`
		}
		add(n, body)
	}
	for _, n := range []string{"resource-invalid", "resource-public", "resource-upper", "resource-restricted", "resource-env-invalid", "resource-env-public"} {
		s := scene{n, "POST", "?resource_urls=public", `{"query_text":"refund policy"}`}
		if n == "resource-invalid" {
			s.query = "?resource_urls=signed"
		}
		if n == "resource-upper" {
			s.query = "?resource_urls=%20PUBLIC%20"
		}
		if strings.Contains(n, "env") {
			s.query = ""
		}
		scenes = append(scenes, s)
	}
	for _, n := range []string{"context-order-1", "context-order-2", "context-order-3", "context-order-4", "context-order-5", "parent-foreign-doc", "relation-foreign-kb", "nearby-foreign-owner", "image-foreign-doc", "faq-invalid-negative", "faq-duplicate-negative", "multi-api-scope"} {
		add(n, `{"query_text":"refund policy"}`)
	}
	for _, n := range []string{"faq-iterative", "faq-iterative-negative", "faq-round-limit", "vector-only-engine", "keyword-only-engine", "faq-unicode-lower"} {
		body := `{"query_text":"refund policy"}`
		if strings.HasPrefix(n, "faq-") {
			body = `{"query_text":"refund policy","match_count":2}`
		}
		if n == "faq-unicode-lower" {
			body = `{"query_text":"İ","disable_keywords_match":true}`
		}
		add(n, body)
	}
	for _, n := range []string{"rerank-metadata-invalid-duplicate", "rerank-metadata-null-duplicate"} {
		add(n, `{"query_text":"refund policy","rerank":{}}`)
	}
	for i := 6; i <= 20; i++ {
		add(fmt.Sprintf("context-order-%d", i), `{"query_text":"refund policy"}`)
	}
	for i := 1; i <= 10; i++ {
		add(fmt.Sprintf("multi-owner-%d", i), `{"query_text":"refund policy","knowledge_base_ids":["kb","kb-second"]}`)
	}
	wanted := map[string]bool{}
	for _, name := range []string{"default", "get-body", "get-query-only", "empty", "null", "array", "keyword-only", "vector-only", "both-disabled", "precomputed", "match-one", "threshold-high", "threshold-negative", "document-filter", "document-missing", "tag-filter", "tag-missing", "skip-enrichment", "rerank-default", "rerank-one", "rerank-error", "shared", "shared-api", "agent", "shared-revoked", "shared-source-left", "api-retrieve", "api-chat", "api-full", "api-denied-kb", "multi-own", "multi-api-scope", "deleted-document", "foreign-chunk-owner", "foreign-chunk-kb", "metadata", "parent", "relation", "image", "embedding-error", "vector-disabled", "keyword-disabled", "vector-only-engine", "keyword-only-engine"} {
		wanted[name] = true
	}
	filtered := []scene{}
	for _, s := range scenes {
		if wanted[s.name] {
			filtered = append(filtered, s)
		}
	}
	scenes = filtered
	for _, name := range []string{"store-foreign", "store-deleted", "store-missing", "store-corrupt-key", "store-unsupported", "store-two", "multi-owner-bound", "bound-default-unregistered"} {
		add(name, `{"query_text":"refund policy"}`)
	}
	address := os.Getenv("WEKNORA_ES_PARITY_URL")
	if !strings.HasPrefix(address, "http://127.0.0.1:") {
		panic("only owned loopback ES allowed")
	}
	engineRequest := func(method, path string, value any) {
		var data []byte
		if value != nil {
			data, _ = json.Marshal(value)
		}
		r, e := http.NewRequest(method, address+path, bytes.NewReader(data))
		must(e)
		r.Header.Set("Content-Type", "application/json")
		response, e := http.DefaultClient.Do(r)
		must(e)
		defer response.Body.Close()
		if response.StatusCode >= 300 {
			panic(fmt.Sprint(response.StatusCode))
		}
	}
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
		sql.SetMaxOpenConns(1)
		if pgMode {
			var database string
			must(db.Raw("SELECT current_database()").Scan(&database).Error)
			if database != "weknora_hybrid_parity" {
				panic("only owned weknora_hybrid_parity accepted")
			}
			must(db.Exec("TRUNCATE vector_stores,chunk_images,embeddings,tenants,knowledge_bases,knowledges,chunks,models,organizations,organization_tenant_members,kb_shares,custom_agents,agent_shares RESTART IDENTITY CASCADE").Error)
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

		indexName := fmt.Sprintf("weknora-public-go-%d", time.Now().UnixNano())
		props := map[string]any{"embedding": map[string]any{"type": "dense_vector", "dims": 2, "index": true, "similarity": "cosine"}, "content": map[string]any{"type": "text"}}
		for _, key := range []string{"chunk_id", "source_id", "knowledge_id", "knowledge_base_id", "tag_id"} {
			props[key] = map[string]any{"type": "keyword"}
		}
		engineRequest("PUT", "/"+indexName, map[string]any{"settings": map[string]any{"number_of_shards": 1, "number_of_replicas": 0}, "mappings": map[string]any{"properties": props}})
		client, e := elasticsearch.NewTypedClient(elasticsearch.Config{Addresses: []string{address}})
		must(e)
		index := res.NewElasticsearchEngineRepository(client, &config.Config{}, &types.IndexConfig{IndexName: indexName})
		must(index.BatchSave(ctx, entries, map[string]any{"embedding": vectors}))
		engineRequest("POST", "/"+indexName+"/_refresh", nil)
		storeRepo := repo.NewVectorStoreRepository(db)
		storeID := "00000000-0000-0000-0000-0000000000es"
		store := &types.VectorStore{ID: storeID, TenantID: owner, Name: "Fixture ES8", EngineType: types.ElasticsearchRetrieverEngineType, ConnectionConfig: types.ConnectionConfig{Addr: address, Version: "8.14.0"}, IndexConfig: types.IndexConfig{IndexName: indexName}}
		must(storeRepo.Create(ctx, store))
		must(exec("UPDATE knowledge_bases SET vector_store_id=? WHERE id IN ('kb','kb-second')", storeID).Error)
		if s.name == "store-foreign" {
			must(exec("UPDATE vector_stores SET tenant_id=9 WHERE id=?", storeID).Error)
		}
		if s.name == "store-deleted" {
			must(exec("UPDATE vector_stores SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", storeID).Error)
		}
		if s.name == "store-missing" {
			must(exec("DELETE FROM vector_stores WHERE id=?", storeID).Error)
		}
		if s.name == "store-corrupt-key" {
			must(exec("UPDATE vector_stores SET connection_config=CAST(? AS BLOB) WHERE id=?", `{"addr":"`+address+`","password":"enc:v1:corrupt"}`, storeID).Error)
		}
		if s.name == "store-unsupported" {
			must(exec("UPDATE vector_stores SET engine_type='unknown' WHERE id=?", storeID).Error)
		}
		registry := retriever.NewRetrieveEngineRegistry(storeRepo, container.NewEngineFactory(db, &config.Config{}, nil))
		// Default physical repo remains registered for backward-compatible unbound routing.
		var defaultIndex interfaces.RetrieveEngineRepository = rsqlite.NewSQLiteRetrieveEngineRepository(db)
		if pgMode {
			defaultIndex = rpg.NewPostgresRetrieveEngineRepository(db)
		}
		must(registry.Register(retriever.NewKVHybridRetrieveEngine(defaultIndex, types.RetrieverEngineType(driver))))
		if s.name == "store-two" || s.name == "multi-owner-bound" {
			second := *store
			second.ID = "00000000-0000-0000-0000-0000000000e2"
			second.Name = "Second ES"
			if s.name == "multi-owner-bound" {
				second.TenantID = 2
			}
			must(storeRepo.Create(ctx, &second))
			must(exec("UPDATE knowledge_bases SET vector_store_id=? WHERE id='kb-second'", second.ID).Error)
			s.body = `{"query_text":"refund policy","knowledge_base_ids":["kb","kb-second"]}`
		}
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
		models := svc.NewModelService(mr, br, nil, nil, nil, nil)
		shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), br, kr, cr, nil)
		agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
		kbs := svc.NewKnowledgeBaseService(br, kr, cr, repo.NewKBShareRepository(db), shares, models, registry, retriever.NewVectorStoreRepoOwnership(storeRepo), repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
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
		if s.name == "bound-default-unregistered" {
			tenant.RetrieverEngines.Engines = []types.RetrieverEngineParams{{RetrieverEngineType: types.QdrantRetrieverEngineType, RetrieverType: types.VectorRetrieverType}}
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
		for _, table := range []string{"vector_stores", "tenants", "knowledge_bases", "knowledges", "chunks", "models", "organizations", "organization_tenant_members", "kb_shares", "custom_agents", "agent_shares"} {
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
		response := httptest.NewRecorder()
		r.ServeHTTP(response, request)
		var body any
		decoder := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
		decoder.UseNumber()
		must(decoder.Decode(&body))
		out = append(out, map[string]any{"name": s.name, "method": s.method, "query": s.query, "request": s.body, "status": response.Code, "body": body, "rows": input, "sql_blobs": blobs, "entries": entries, "vectors": vectors, "model_http": requests, "caller": 1, "machine": machine, "scope": scope, "tenant_context": tenant, "resource_default": os.Getenv("RESOURCE_URL_MODE"), "driver": driver})
		engineRequest("DELETE", "/"+indexName, nil)
		server.Close()
		must(sql.Close())
		must(os.RemoveAll(folder))
	}
	data, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
	fmt.Println("exported", len(out), "production Go bound-ES hybrid HTTP/SQL/model scenarios")
}
