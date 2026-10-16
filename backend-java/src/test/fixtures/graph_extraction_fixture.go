//go:build ignore

// Actual production graph worker, provider HTTP, SQL counters/spans and live Neo4j/APOC.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	graphrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/neo4j"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/alicebob/miniredis/v2"
	"github.com/hibiken/asynq"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
	nc "github.com/neo4j/neo4j-go-driver/v6/neo4j/config"
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
func main() {
	logger.SetOutput(os.Stderr)
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	utils.ResetSSRFWhitelistForTest()
	uri := os.Getenv("GRAPH_ORACLE_URI")
	if uri == "" {
		panic("GRAPH_ORACLE_URI is required: use an isolated Neo4j/APOC instance")
	}
	driver, e := neo4j.NewDriver(uri, neo4j.NoAuth())
	must(e)
	ctx := context.Background()
	defer driver.Close(ctx)
	session := driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)
	result, e := session.Run(ctx, "RETURN apoc.version() AS version", nil)
	must(e)
	record, e := result.Single(ctx)
	must(e)
	v, _ := record.Get("version")
	fmt.Fprintln(os.Stderr, "Actual APOC:", v)
	names := []string{"success", "legacy", "ordinal-zero", "empty-graph", "dict", "fenced", "truncated-fence", "prose-json", "duplicate-node", "unknown-endpoints", "self-relation", "unsupported", "number-values", "invalid-json", "invalid-list", "chat-error", "chat-error-final", "model-missing", "model-missing-final", "disabled", "override", "custom", "missing-chunk", "disappeared-during-chat", "cancelled", "deleting", "cancel-during-chat", "superseded", "superseded-during-chat", "revision-during-chat", "foreign-document", "foreign-kb", "foreign-tenant", "missing-knowledge", "graph-error", "graph-error-final", "missing-base", "base-foreign-tenant", "cancel-during-error", "binding-during-chat", "long-preview", "custom-percent"}
	out := []any{}
	for _, name := range names {
		_, e = session.Run(ctx, "MATCH(n) WHERE n.kg IN ['graph-oracle-doc','foreign-document'] DETACH DELETE n", nil)
		must(e)
		dir, e := os.MkdirTemp("", "weknora-go-graph-")
		must(e)
		db, e := gorm.Open(sqlite.Open(filepath.Join(dir, "test.db")), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
		must(e)
		sql, e := db.DB()
		must(e)
		sql.SetMaxOpenConns(1)
		for _, m := range []string{"000000_init", "000002_knowledge_folder_path", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(db.Exec(string(b)).Error)
		}
		tr := repo.NewTenantRepository(db)
		must(tr.CreateTenant(ctx, &types.Tenant{ID: 1, Name: "Owner", Business: "test", Status: "active"}))
		kr := repo.NewKnowledgeRepository(db)
		cr := repo.NewChunkRepository(db)
		br := repo.NewKnowledgeBaseRepository(db)
		kb := &types.KnowledgeBase{ID: "graph-oracle-kb", TenantID: 1, Name: "Graph", Type: "document", SummaryModelID: "summary", IndexingStrategy: types.IndexingStrategy{GraphEnabled: true}, ExtractConfig: &types.ExtractConfig{Enabled: true, Tags: []string{"owns"}, Text: "Example text", Nodes: []*types.GraphNode{{Name: "Example", Attributes: []string{"sample"}}}, Relations: []*types.GraphRelation{{Node1: "Example", Node2: "Other", Type: "owns"}}}}
		if name == "disabled" {
			kb.ExtractConfig.Enabled = false
		}
		if name == "custom-percent" {
            kb.ExtractConfig.CustomInstructions = "  Retain 10% growth, literal %s and %q, %% and 100%  "
        }
        if name == "custom" {
			kb.ExtractConfig.CustomInstructions = "  Contract entities 中文  "
		}
		must(br.CreateKnowledgeBase(ctx, kb))
		state := "finalizing"
		if name == "cancelled" || name == "deleting" {
			state = name
		}
		knowledge := &types.Knowledge{ID: "graph-oracle-doc", TenantID: 1, KnowledgeBaseID: kb.ID, Type: "file", Title: "Graph document", ParseStatus: state, SummaryStatus: "completed", PendingSubtasksCount: 2, EnableStatus: "enabled", ErrorMessage: "old error"}
		if name == "override" {
			must(knowledge.SetProcessOverrides(&types.KnowledgeProcessOverrides{ExtractConfig: &types.ExtractConfig{Enabled: true, Text: "Document example", Tags: []string{"uses"}, CustomInstructions: " Override entities "}}))
		}
		must(kr.CreateKnowledge(ctx, knowledge))
		body := "Alice owns the release process 中文."
		if name == "long-preview" {
			body = "  中文\n\t" + strings.Repeat("内容  ", 100)
		}
		chunk := &types.Chunk{ID: "graph-oracle-chunk", TenantID: 1, KnowledgeID: knowledge.ID, KnowledgeBaseID: kb.ID, Content: body, ChunkType: types.ChunkTypeText, IsEnabled: true, ContentRevision: 4}
		must(cr.CreateChunks(ctx, []*types.Chunk{chunk}))
		if name == "foreign-document" {
			must(db.Exec("UPDATE chunks SET knowledge_id='foreign-document'").Error)
		}
		if name == "foreign-kb" {
			other := *kb
			other.ID = "foreign-kb"
			must(br.CreateKnowledgeBase(ctx, &other))
			must(db.Exec("UPDATE chunks SET knowledge_base_id='foreign-kb'").Error)
		}
		if name == "foreign-tenant" {
			must(db.Exec("UPDATE chunks SET tenant_id=2").Error)
		}
		if name == "missing-chunk" {
			must(db.Exec("DELETE FROM chunks").Error)
		}
		tracker := svc.NewSpanTracker(repo.NewKnowledgeSpanRepository(db), db)
		track := context.WithValue(ctx, types.TenantIDContextKey, uint64(1))
		_, attempt, e := tracker.OpenAttempt(track, knowledge.ID, "graph-oracle")
		must(e)
		tracker.BeginStage(track, knowledge.ID, attempt, types.StagePostProcess, nil)
		p := types.ExtractChunkPayload{TenantID: 1, ChunkID: chunk.ID, ModelID: "summary", KnowledgeID: knowledge.ID, Attempt: attempt, ChunkIndex: 2}
		if name == "legacy" {
			p.KnowledgeID = ""
			p.Attempt = 0
		}
		if name == "ordinal-zero" {
			p.ChunkIndex = 0
		}
		if name == "superseded" {
			_, _, e = tracker.OpenAttempt(track, knowledge.ID, "successor")
			must(e)
		}
		if name == "missing-base" {
			must(db.Exec("DELETE FROM knowledge_bases WHERE id=?", kb.ID).Error)
		}
		if name == "base-foreign-tenant" {
			must(db.Exec("UPDATE knowledge_bases SET tenant_id=2 WHERE id=?", kb.ID).Error)
		}
		if name == "missing-knowledge" {
			must(db.Exec("DELETE FROM knowledges").Error)
		}
		reply := `[{"entity":"Alice","entity_attributes":["Owner"]},{"entity":"Release"},{"entity1":"Alice","entity2":"Release","relation":"owns"}]`
		switch name {
		case "empty-graph":
			reply = "[]"
		case "dict":
			reply = `{"entity":"Alice"}`
		case "fenced":
			reply = "```json\n" + reply + "\n```"
		case "truncated-fence":
			reply = "```json\n" + reply
		case "prose-json":
			reply = "Extracted data:\n" + reply + "\nEnd."
		case "duplicate-node":
			reply = `[{"entity":"Alice","entity_attributes":["First"]},{"entity":"Alice","entity_attributes":["Second"]}]`
		case "unknown-endpoints":
			reply = `[{"entity1":"Alice","entity2":"Release","relation":"owns"}]`
		case "self-relation":
			reply = `[{"entity1":"Alice","entity2":"Alice","relation":"owns"}]`
		case "unsupported":
			reply = `[{"foo":"x"}]`
		case "number-values":
			reply = `[{"entity":12,"entity_attributes":[true,3,null,{"x":1}]},{"entity1":12,"entity2":13}]`
		case "invalid-json":
			reply = "not a graph"
		case "invalid-list":
			reply = "[1]"
		}
		requests := []any{}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var body map[string]any
			must(json.NewDecoder(r.Body).Decode(&body))
			requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
			w.Header().Set("Content-Type", "application/json")
			if name == "cancel-during-error" {
				must(db.Exec("UPDATE knowledges SET parse_status='cancelled'").Error)
			}
			if strings.HasPrefix(name, "chat-error") || name == "cancel-during-error" {
				w.WriteHeader(502)
				fmt.Fprint(w, `{"error":{"message":"synthetic provider failure"}}`)
				return
			}
			switch name {
			case "binding-during-chat":
				must(db.Exec("UPDATE chunks SET knowledge_id='foreign-document'").Error)
			case "cancel-during-chat":
				must(db.Exec("UPDATE knowledges SET parse_status='cancelled'").Error)
			case "superseded-during-chat":
				_, _, e := tracker.OpenAttempt(track, knowledge.ID, "successor")
				must(e)
			case "revision-during-chat":
				must(db.Exec("UPDATE chunks SET content_revision=5").Error)
			case "disappeared-during-chat":
				must(db.Exec("DELETE FROM chunks").Error)
			}
			must(json.NewEncoder(w).Encode(map[string]any{"id": "graph-chat", "object": "chat.completion", "choices": []any{map[string]any{"index": 0, "message": map[string]any{"role": "assistant", "content": reply}, "finish_reason": "stop"}}}))
		}))
		mr := repo.NewModelRepository(db)
		must(mr.Create(ctx, &types.Model{ID: "summary", TenantID: 1, Name: "unknown", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{BaseURL: server.URL + "/v1", APIKey: "synthetic-key", Provider: "generic"}}))
		models := svc.NewModelService(mr, br, nil, nil, nil, nil)
		if strings.HasPrefix(name, "model-missing") {
			p.ModelID = "missing"
		}
		description := "Extract relation types: %s."
		cfg := &config.Config{ExtractManager: &config.ExtractManagerConfig{ExtractGraph: &types.PromptTemplateStructured{Description: description}}}
		engine := graphrepo.NewNeo4jRepository(driver)
		if strings.HasPrefix(name, "graph-error") {
			bad, e := neo4j.NewDriver("bolt://127.0.0.1:1", neo4j.NoAuth(), func(c *nc.Config) { c.SocketConnectTimeout = time.Second; c.MaxTransactionRetryTime = 0 })
			must(e)
			defer bad.Close(ctx)
			engine = graphrepo.NewNeo4jRepository(bad)
		}
		service := svc.NewChunkExtractService(cfg, models, br, kr, cr, engine, tracker)
		raw, e := json.Marshal(p)
		must(e)
		retry := 0
		if strings.HasSuffix(name, "-final") {
			retry = 3
		}
		redis, e := miniredis.Run()
		must(e)
		opt := asynq.RedisClientOpt{Addr: redis.Addr()}
		worker := asynq.NewServer(opt, asynq.Config{Concurrency: 1, Queues: map[string]int{"graph-oracle": 1}, RetryDelayFunc: func(int, error, *asynq.Task) time.Duration { return 0 }, DelayedTaskCheckInterval: 10 * time.Millisecond, TaskCheckInterval: 10 * time.Millisecond, ShutdownTimeout: time.Second})
		result := make(chan error, 1)
		must(worker.Start(asynq.HandlerFunc(func(c context.Context, t *asynq.Task) error {
			n, _ := asynq.GetRetryCount(c)
			if n < retry {
				return fmt.Errorf("fixture retry setup")
			}
			result <- service.Handle(c, t)
			return nil
		})))
		client := asynq.NewClient(opt)
		_, e = client.Enqueue(asynq.NewTask(types.TypeChunkExtract, raw), asynq.Queue("graph-oracle"), asynq.MaxRetry(3), asynq.Timeout(30*time.Minute))
		must(e)
		var err error
		select {
		case err = <-result:
		case <-time.After(time.Minute):
			panic("graph worker timeout")
		}
		worker.Shutdown()
		client.Close()
		redis.Close()
		counts, e := graphrepo.NewNeo4jRepository(driver).(interface {
			CountKnowledgeGraph(context.Context, types.NameSpace) (*types.KnowledgeGraphCounts, error)
		}).CountKnowledgeGraph(ctx, types.NameSpace{KnowledgeBase: kb.ID, Knowledge: knowledge.ID})
		must(e)
		detail, e := graphrepo.NewNeo4jRepository(driver).(interface {
			GetChunkGraph(context.Context, types.NameSpace, string) (*types.ChunkGraphDetail, error)
		}).GetChunkGraph(ctx, types.NameSpace{KnowledgeBase: kb.ID, Knowledge: knowledge.ID}, chunk.ID)
		must(e)
		for i := range detail.Nodes {
			detail.Nodes[i].ID = ""
		}
		for i := range detail.Relations {
			detail.Relations[i].ID = ""
		}
		spanRows := []types.KnowledgeProcessingSpan{}
		must(db.Where("name LIKE 'postprocess.graph.chunk[%]'").Order("created_at").Find(&spanRows).Error)
		var span any
		for _, s := range spanRows {
			var input, output any
			if len(s.Input) > 0 {
				input = s.Input
			}
			if len(s.Output) > 0 {
				output = s.Output
			}
			span = map[string]any{"name": s.Name, "status": s.Status, "input": input, "output": output, "error_code": s.ErrorCode}
		}
		var stored types.Knowledge
		db.Where("id=?", knowledge.ID).First(&stored)
		out = append(out, map[string]any{"name": name, "content": body, "payload": p, "reply": reply, "description": description, "retry": retry, "requests": requests, "failed": err != nil, "span": span, "counts": counts, "detail": detail, "knowledge": map[string]any{"parse_status": stored.ParseStatus, "summary_status": stored.SummaryStatus, "pending_subtasks_count": stored.PendingSubtasksCount, "error_message": stored.ErrorMessage}})
		server.Close()
		must(sql.Close())
		must(os.RemoveAll(dir))
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
