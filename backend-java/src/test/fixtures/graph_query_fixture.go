//go:build ignore

package main

import (
	"context"
	"encoding/json"
	"fmt"
	access "github.com/Tencent/WeKnora/internal/application/access"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	graphrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/neo4j"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	pipeline "github.com/Tencent/WeKnora/internal/application/service/chat_pipeline"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
	"go.uber.org/dig"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

var migrations = []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"}

func main() {
	logger.SetOutput(os.Stderr)
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	utils.ResetSSRFWhitelistForTest()
	must(os.Setenv("NEO4J_ENABLE", "true"))
	old := os.Stdout
	os.Stdout = os.Stderr
	cfg, err := config.LoadConfig()
	os.Stdout = old
	must(err)
	ctx := context.Background()
	driver, err := neo4j.NewDriver(os.Getenv("GRAPH_ORACLE_URI"), neo4j.NoAuth())
	must(err)
	defer driver.Close(ctx)
	graph := graphrepo.NewNeo4jRepository(driver)
	run := fmt.Sprintf("rag-oracle-%d", time.Now().UnixNano())
	out := []any{}
	kinds := []string{"own-file", "own-base", "shared-file", "shared-base", "agent-grant", "empty-authorized-targets", "wrong-target-owner", "foreign-chunk-owner", "outside-document", "tag-resolved-document", "physical-tag-hit", "physical-tag-miss", "disabled-chunk", "deleted-document", "orphan-chunk", "metadata-values", "metadata-null", "metadata-array", "metadata-invalid", "existing-id", "existing-unicode-content", "blank-content", "thirty-candidate-cap", "mixed-base-file", "valid-image-child", "foreign-image-child", "wrong-kb-image-child", "wrong-document-image-child", "disabled-image-child", "grandchild-image", "scanned-pdf-page", "typed-image-invalid", "multiple-image-urls", "rewritten-query", "provider-failure", "invalid-model-output", "empty-model-nodes", "greeting", "web-search-disabled", "web-search-enabled", "neo4j-disabled", "extract-disabled", "share-revoked-during-chat", "owner-changed-during-chat", "extract-disabled-during-chat", "api-key-allowed", "api-key-denied", "api-key-chat-only", "api-key-retrieve-only", "api-key-full-access", "shared-api-key-chat-only", "blob-metadata", "blob-source-locators", "blob-kb-config", "image-attrs-numbers"}
	for _, kind := range kinds {
		dir, err := os.MkdirTemp("", "weknora-go-rag-oracle-")
		must(err)
		db, err := gorm.Open(sqlite.Open(filepath.Join(dir, "oracle.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(err)
		sql, err := db.DB()
		must(err)
		for _, name := range migrations {
			data, err := os.ReadFile("migrations/sqlite/" + name + ".up.sql")
			must(err)
			must(db.Exec(string(data)).Error)
		}
		kb := run + "-" + kind + "-kb"
		doc := kb + "-doc"
		peer := kb + "-peer"
		kb2 := kb + "-second"
		doc2 := kb2 + "-doc"
		owner := uint64(1)
		shared := strings.HasPrefix(kind, "shared-") || kind == "agent-grant" || kind == "share-revoked-during-chat"
		if shared {
			owner = 2
		}
		ctx := types.WithCaller(context.WithValue(context.Background(), types.TenantIDContextKey, uint64(1)), types.Caller{TenantID: 1, UserID: "caller", Role: types.TenantRoleViewer})
		kr := repo.NewKnowledgeRepository(db)
		cr := repo.NewChunkRepository(db)
		br := repo.NewKnowledgeBaseRepository(db)
		must(br.CreateKnowledgeBase(ctx, &types.KnowledgeBase{ID: kb, TenantID: owner, Name: "Graph query", Type: "document", ExtractConfig: &types.ExtractConfig{Enabled: kind != "extract-disabled"}}))
		for _, id := range []string{doc, peer} {
			must(kr.CreateKnowledge(ctx, &types.Knowledge{ID: id, TenantID: owner, KnowledgeBaseID: kb, Title: "Graph document", Type: "file", FileName: "source.pdf", Source: "manual", Channel: "web", EnableStatus: "enabled"}))
		}
		org := kb + "-org"
		if shared {
			must(db.Exec("INSERT INTO organizations(id,name,owner_id,owner_tenant_id) VALUES(?,?,?,?)", org, "Sharing", "owner", 2).Error)
			for _, tenant := range []int{1, 2} {
				must(db.Exec("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES(?,?,?,?)", fmt.Sprintf("%s-member-%d", org, tenant), org, tenant, "admin").Error)
			}
			if kind != "agent-grant" {
				must(db.Exec("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,shared_by_user_id,source_tenant_id,permission) VALUES(?,?,?,?,?,?)", org+"-share", kb, org, "owner", 2, "admin").Error)
			}
		}
		if kind == "agent-grant" {
			agent := &types.CustomAgent{ID: kb + "-agent", Name: "Shared graph agent", TenantID: 2, Config: types.CustomAgentConfig{KBSelectionMode: "selected", KnowledgeBases: []string{kb}}}
			must(repo.NewCustomAgentRepository(db).CreateAgent(ctx, agent))
			must(db.Exec("INSERT INTO agent_shares(id,agent_id,organization_id,shared_by_user_id,source_tenant_id,permission) VALUES(?,?,?,?,?,?)", org+"-agent-share", agent.ID, org, "owner", 2, "viewer").Error)
			service := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, br)
			authorized, err := service.GetSharedAgentForTenant(ctx, 1, types.TenantRoleViewer, agent.ID, 2)
			must(err)
			ctx = types.WithExecutionTenant(access.WithSharedAgent(ctx, authorized), 1)
		}

		if strings.Contains(kind, "api-key-") {
			allow := kb
			if kind == "api-key-denied" {
				allow = kb + "-other"
			}
			capabilities := types.StringArray{"chat", "retrieve"}
			if kind == "api-key-chat-only" || kind == "shared-api-key-chat-only" {
				capabilities = types.StringArray{"chat"}
			}
			if kind == "api-key-retrieve-only" {
				capabilities = types.StringArray{"retrieve"}
			}
			if kind == "api-key-full-access" {
				capabilities = nil
			}
			ctx = types.WithTenantAPIKeyScope(ctx, types.TenantAPIKeyScope{KeyID: 7, KnowledgeBaseIDs: types.StringArray{allow}, FullAccess: kind == "api-key-full-access", Capabilities: capabilities})
		}
		chunks := []*types.Chunk{{ID: doc + "-chunk", TenantID: owner, KnowledgeID: doc, KnowledgeBaseID: kb, Content: "Alice owns the release", ChunkType: types.ChunkTypeText, ChunkIndex: 3, StartAt: 7, EndAt: 34, ParentChunkID: "parent-reference", IsEnabled: true}}
		hit := chunks[0].ID
		graphDoc := doc
		target := &types.SearchTarget{Type: types.SearchTargetTypeKnowledge, KnowledgeBaseID: kb, TenantID: owner, KnowledgeIDs: []string{doc}}
		if kind == "own-base" || kind == "shared-base" {
			target.Type = types.SearchTargetTypeKnowledgeBase
			target.KnowledgeIDs = nil
		}
		if kind == "wrong-target-owner" {
			target.TenantID = 9
		}
		if kind == "foreign-chunk-owner" {
			chunks[0].TenantID = 9
		}
		if kind == "outside-document" || kind == "tag-resolved-document" {
			chunks[0].KnowledgeID = peer
			chunks[0].ID = peer + "-chunk"
			hit = chunks[0].ID
		}
		if kind == "tag-resolved-document" {
			graphDoc = peer
			target.ScopeTagIDs = []string{"logical-tag"}
		}
		if kind == "physical-tag-hit" || kind == "physical-tag-miss" {
			target.TagIDs = []string{"wanted"}
			chunks[0].TagID = "wanted"
			if kind == "physical-tag-miss" {
				chunks[0].TagID = "other"
			}
		}
		if kind == "disabled-chunk" {
			chunks[0].IsEnabled = false
		}
		if kind == "deleted-document" {
			must(db.Exec("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", doc).Error)
		}
		if kind == "orphan-chunk" {
			chunks[0].KnowledgeID = kb + "-missing"
		}
		if kind == "metadata-values" {
			must(db.Exec("UPDATE knowledges SET metadata=? WHERE id=?", `{"float":1e6,"nil":null,"bool":true,"array":[1,"x"],"object":{"b":2,"a":1}}`, doc).Error)
			chunks[0].Metadata = types.JSON(`{"generated_questions":[{"id":"q","question":"Who?","content_revision":0}]}`)
		}
		for _, value := range []struct{ name, raw string }{{"metadata-null", "null"}, {"metadata-array", "[]"}, {"metadata-invalid", "{"}} {
			if kind == value.name {
				must(db.Exec("UPDATE knowledges SET metadata=? WHERE id=?", value.raw, doc).Error)
			}
		}
		if kind == "blank-content" {
			chunks[0].Content = " \u00a0\n"
		}
		hits := []string{hit}
		if kind == "thirty-candidate-cap" {
			chunks = nil
			hits = nil
			for i := 0; i < 35; i++ {
				id := fmt.Sprintf("%s-chunk-%02d", doc, i)
				chunks = append(chunks, &types.Chunk{ID: id, TenantID: owner, KnowledgeID: doc, KnowledgeBaseID: kb, Content: fmt.Sprintf("Candidate %d", i), IsEnabled: true, ChunkType: "text"})
				hits = append(hits, id)
			}
		}
		img := func(id, typ string) *types.Chunk {
			return &types.Chunk{ID: doc + "-" + id, TenantID: owner, KnowledgeID: doc, KnowledgeBaseID: kb, ParentChunkID: chunks[0].ID, Content: "Recognized <b>caption</b> &amp; text", ChunkType: typ, IsEnabled: true, ImageInfo: `[{"url":"https://image.invalid/a","caption":"caption","ocr_text":"recognized"}]`}
		}
		if strings.Contains(kind, "image") || kind == "scanned-pdf-page" {
			child := img("image", "image_ocr")
			switch kind {
			case "foreign-image-child":
				child.TenantID = 9
			case "wrong-kb-image-child":
				child.KnowledgeBaseID = kb2
			case "wrong-document-image-child":
				child.KnowledgeID = peer
			case "disabled-image-child":
				child.IsEnabled = false
			case "typed-image-invalid":
				child.ImageInfo = `[{"url":7}]`
			case "grandchild-image":
				text := img("text-child", "text")
				text.ImageInfo = ""
				child.ParentChunkID = text.ID
				chunks = append(chunks, text)
			case "image-attrs-numbers":
				child.ImageInfo = `[{"url":"https://image.invalid/a","attrs":{"schema":"attrs/2","source":{"schema":"s","prompt":"p","custom":true},"attrs":{"z":[1e6,1e-8,1e-7,1e20,1e21,-0,true,null,"<&"],"a":{"z":2,"a":1}}}}]`
			case "multiple-image-urls":
				child.ImageInfo = `[{"url":"https://image.invalid/z","caption":"z"},{"url":"https://image.invalid/a","caption":"a"}]`
			case "scanned-pdf-page":
				chunks[0].SourceLocators = types.SourceLocators{{Type: "pdf", Page: 2, Quote: "![page](url)"}}
				child.SourceLocators = types.SourceLocators{{Type: "pdf", Page: 2}}
			}
			chunks = append(chunks, child)
		}
		must(cr.CreateChunks(ctx, chunks))
		if kind == "blob-metadata" {
			must(db.Exec("UPDATE knowledges SET metadata=CAST(? AS BLOB) WHERE id=?", `{"blob":true}`, doc).Error)
			must(db.Exec("UPDATE chunks SET metadata=CAST(? AS BLOB) WHERE id=?", `{"generated_questions":[{"id":"q","question":"Who?"}]}`, hit).Error)
		}
		if kind == "blob-source-locators" {
			must(db.Exec("UPDATE chunks SET source_locators=CAST(? AS BLOB) WHERE id=?", `[{"type":"text","start":1,"end":9}]`, hit).Error)
		}
		if kind == "blob-kb-config" {
			must(db.Exec("UPDATE knowledge_bases SET extract_config=CAST(? AS BLOB) WHERE id=?", `{"enabled":true}`, kb).Error)
		}
		if kind == "disabled-chunk" {
			must(db.Exec("UPDATE chunks SET is_enabled=0 WHERE id=?", hit).Error)
		}
		if kind == "disabled-image-child" {
			must(db.Exec("UPDATE chunks SET is_enabled=0 WHERE id=?", doc+"-image").Error)
		}
		graphSeed := &types.GraphData{Node: []*types.GraphNode{{Name: "Alice", Attributes: []string{}, Chunks: hits}, {Name: "Release", Attributes: []string{}, Chunks: hits}}, Relation: []*types.GraphRelation{{Node1: "Alice", Node2: "Release", Type: "owns"}}}
		must(graph.AddGraph(ctx, types.NameSpace{KnowledgeBase: kb, Knowledge: graphDoc}, []*types.GraphData{graphSeed}))
		targets := types.SearchTargets{target}
		seeds := []any{map[string]any{"kb": kb, "doc": graphDoc, "graph": graphSeed}}
		if kind == "mixed-base-file" {
			must(br.CreateKnowledgeBase(ctx, &types.KnowledgeBase{ID: kb2, TenantID: 1, Name: "Second", Type: "document", ExtractConfig: &types.ExtractConfig{Enabled: true}}))
			must(kr.CreateKnowledge(ctx, &types.Knowledge{ID: doc2, TenantID: 1, KnowledgeBaseID: kb2, Title: "Second", Type: "file"}))
			must(cr.CreateChunks(ctx, []*types.Chunk{{ID: doc2 + "-chunk", TenantID: 1, KnowledgeID: doc2, KnowledgeBaseID: kb2, Content: "Second scope document", ChunkType: "text", IsEnabled: true}}))
			other := &types.GraphData{Node: []*types.GraphNode{{Name: "Alice", Chunks: []string{doc2 + "-chunk"}}, {Name: "Release", Chunks: []string{doc2 + "-chunk"}}}, Relation: graphSeed.Relation}
			must(graph.AddGraph(ctx, types.NameSpace{KnowledgeBase: kb2, Knowledge: doc2}, []*types.GraphData{other}))
			target.Type = types.SearchTargetTypeKnowledgeBase
			target.KnowledgeIDs = nil
			targets = append(targets, &types.SearchTarget{Type: types.SearchTargetTypeKnowledge, KnowledgeBaseID: kb2, TenantID: 1, KnowledgeIDs: []string{doc2}})
			seeds = append(seeds, map[string]any{"kb": kb2, "doc": doc2, "graph": other})
		}
		if kind == "empty-authorized-targets" {
			targets = types.SearchTargets{}
		}
		inputRows := map[string]any{}
		sqlBlobs := map[string]map[string][]string{}
		for _, table := range []string{"knowledge_bases", "knowledges", "chunks", "organizations", "organization_tenant_members", "kb_shares", "custom_agents", "agent_shares"} {
			var rows []map[string]any
			must(db.Table(table).Order("id").Find(&rows).Error)
			for _, row := range rows {
				var blobs []string
				for key, value := range row {
					if b, ok := value.([]byte); ok {
						row[key] = string(b)
					}
					var storedType string
					must(sql.QueryRow(`SELECT typeof("`+key+`") FROM "`+table+`" WHERE id=?`, row["id"]).Scan(&storedType))
					if storedType == "blob" {
						blobs = append(blobs, key)
					}
				}
				if len(blobs) > 0 {
					if sqlBlobs[table] == nil {
						sqlBlobs[table] = map[string][]string{}
					}
					sqlBlobs[table][fmt.Sprint(row["id"])] = blobs
				}
			}
			inputRows[table] = rows
		}
		requests := []any{}
		response := `[{"entity":"Alice"}]`
		if kind == "invalid-model-output" {
			response = "broken"
		}
		if kind == "empty-model-nodes" {
			response = "[]"
		}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var request map[string]any
			must(json.NewDecoder(r.Body).Decode(&request))
			requests = append(requests, request)
			switch kind {
			case "share-revoked-during-chat":
				must(db.Exec("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
			case "owner-changed-during-chat":
				must(db.Exec("UPDATE knowledge_bases SET tenant_id=9 WHERE id=?", kb).Error)
			case "extract-disabled-during-chat":
				must(db.Exec(`UPDATE knowledge_bases SET extract_config='{"enabled":false}' WHERE id=?`, kb).Error)
			}
			if kind == "provider-failure" {
				w.WriteHeader(400)
				must(json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"message": "fixture rejection"}}))
				return
			}
			must(json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": response}}}}))
		}))
		mr := repo.NewModelRepository(db)
		modelID := kb + "-model"
		must(mr.Create(ctx, &types.Model{ID: modelID, TenantID: 1, Name: "graph-query-fixture", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic"}}))
		models := svc.NewModelService(mr, br, nil, nil, nil, nil)
		shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), br, kr, cr, nil)
		knowledge, err := svc.NewKnowledgeService(cfg, kr, nil, nil, nil, nil, nil, cr, nil, nil, nil, nil, nil, models, nil, nil, graph, nil, nil, nil, shares, nil, nil, nil, nil, nil, nil)
		must(err)
		cm := &types.ChatManage{PipelineRequest: types.PipelineRequest{TenantID: 1, KnowledgeBaseIDs: []string{kb}, KnowledgeIDs: []string{doc}, SearchTargets: targets, ChatModelID: modelID, Query: "What does Alice own?"}, PipelineState: types.PipelineState{Intent: types.IntentKBSearch}}
		if kind == "rewritten-query" {
			cm.RewriteQuery = " \u00a0Alice release owner?\u00a0 "
		}
		if kind == "greeting" {
			cm.Intent = types.IntentGreeting
		}
		if strings.HasPrefix(kind, "web-search-") {
			cm.Intent = types.IntentWebSearch
			cm.WebSearchEnabled = kind == "web-search-enabled"
		}
		if kind == "existing-id" {
			cm.SearchResult = []*types.SearchResult{{ID: hit, Content: "Already retrieved", Score: .7, MatchType: types.MatchTypeEmbedding}}
		}
		if kind == "existing-unicode-content" {
			cm.SearchResult = []*types.SearchResult{{ID: kb + "-existing", Content: " \u00a0ALICE\nOWNS\tTHE\u3000RELEASE ", Score: .7, MatchType: types.MatchTypeEmbedding}}
		}
		existing := cm.SearchResult
		must(os.Setenv("NEO4J_ENABLE", fmt.Sprint(kind != "neo4j-disabled")))
		// Use the same typed dig.Invoke constructors as production container.go.
		container := dig.New()
		must(container.Provide(pipeline.NewEventManager))
		must(container.Provide(func() interfaces.ModelService { return models }))
		must(container.Provide(func() interfaces.KnowledgeBaseRepository { return br }))
		must(container.Provide(func() interfaces.KnowledgeService { return knowledge }))
		must(container.Provide(func() interfaces.KnowledgeRepository { return kr }))
		must(container.Provide(func() *config.Config { return cfg }))
		must(container.Provide(func() interfaces.KBShareService { return shares }))
		must(container.Provide(func() interfaces.RetrieveGraphRepository { return graph }))
		must(container.Provide(func() interfaces.ChunkRepository { return cr }))
		must(container.Invoke(pipeline.NewPluginExtractEntityAuthorized))
		must(container.Invoke(pipeline.NewPluginSearchEntityAuthorized))
		var exErr, searchErr *pipeline.PluginError
		must(container.Invoke(func(manager *pipeline.EventManager) {
			exErr = manager.Trigger(ctx, types.QUERY_UNDERSTAND, cm)
			searchErr = manager.Trigger(ctx, types.ENTITY_SEARCH, cm)
		}))

		out = append(out, map[string]any{"name": kind, "rows": inputRows, "sql_blobs": sqlBlobs, "graphs": seeds, "template": cfg.ExtractManager.ExtractEntity, "query": cm.Query, "rewrite": cm.RewriteQuery, "intent": cm.Intent, "web_search": cm.WebSearchEnabled, "targets": targets, "existing": existing, "provider_output": response, "model_http": requests, "entities": cm.Entity, "entity_kbs": cm.EntityKBIDs, "entity_documents": cm.EntityKnowledge, "graph": cm.GraphResult, "results": cm.SearchResult, "extract_error": fmt.Sprint(exErr), "search_error": fmt.Sprint(searchErr)})
		server.Close()
		must(os.Setenv("NEO4J_ENABLE", "true"))
		must(graph.DelGraph(ctx, []types.NameSpace{{KnowledgeBase: kb, Knowledge: doc}, {KnowledgeBase: kb, Knowledge: peer}, {KnowledgeBase: kb2, Knowledge: doc2}}))
		must(sql.Close())
		must(os.RemoveAll(dir))
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
