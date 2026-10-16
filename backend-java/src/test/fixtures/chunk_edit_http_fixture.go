//go:build ignore

// Actual production Go editable chunk HTTP, revisions and physical model/index checkpoints; source-specific float32/SQL oracle.
package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	pgindex "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	sr "github.com/Tencent/WeKnora/internal/application/repository/retriever/sqlite"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	retrieve "github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/alicebob/miniredis/v2"
	sqlitevec "github.com/asg017/sqlite-vec-go-bindings/cgo"
	"github.com/gin-gonic/gin"
	"github.com/hibiken/asynq"
	"github.com/panjf2000/ants/v2"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"hash/fnv"
	"math"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

type capture struct {
	client *asynq.Client
	fail   bool
	rows   []any
}

func (c *capture) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, errors.New("controlled enqueue failure")
	}
	info, e := c.client.Enqueue(t, opts...)
	if e != nil {
		return nil, e
	}
	var p any
	must(json.Unmarshal(t.Payload(), &p))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": p, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}

type scene struct{ name, operation, variant, target, body string }

func main() {
	time.Local = time.UTC
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	out := []any{}
	collect := func(s scene) {
		if len(os.Args) > 2 && s.name != os.Args[2] {
			return
		}
		out = append(out, run(s))
	}
	sqlitevec.Auto()
	if os.Getenv("WEKNORA_CHUNK_EDIT_PARITY_PG_DSN") != "" {
		must(os.Setenv("RETRIEVE_DRIVER", "postgres"))
	} else {
		must(os.Setenv("RETRIEVE_DRIVER", "sqlite"))
	}
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	must(os.Setenv("SYSTEM_AES_KEY", ""))
	utils.ResetSSRFWhitelistForTest()
	regenerateMode := os.Getenv("WEKNORA_CHUNK_REGENERATE_PARITY") == "true"
	questionMode := os.Getenv("WEKNORA_CHUNK_QUESTION_PARITY") == "true" || regenerateMode
	if questionMode {
		variants := []string{"ok", "moving", "missing-kb", "deleted-kb", "foreign-document", "deleted-document", "foreign-chunk", "deleted-chunk", "wrong-chunk-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "non-text", "model-missing", "provider-error", "index-error", "disabled", "metadata-array", "question-fold", "question-duplicate", "question-long-id", "question-whitespace", "title-spaces", "context-header-spaces", "vector-only-blob", "keyword-only-blob", "wiki-only-blob", "no-index-blob", "invalid-index-blob", "edit-during-provider", "metadata-during-provider", "doc-owner-during-provider", "kb-owner-during-provider", "chunk-owner-during-provider"}
		if regenerateMode {
			variants = append(variants, "no-summary-model", "chat-model-missing", "prompt-missing", "chat-error", "chat-empty", "empty-content", "count-one", "count-max", "count-negative", "overrides-count", "neighbor-valid", "neighbor-foreign-doc", "neighbor-foreign-owner", "neighbor-foreign-kb", "custom-instructions", "chat-owner-change", "chat-kb-owner-change", "chat-chunk-owner-change", "chat-edit", "chat-metadata", "chat-title", "chat-embedding-model-change", "embedding-edit", "embedding-disable", "embedding-metadata", "embedding-doc-owner", "embedding-kb-owner", "embedding-chunk-owner", "embedding-title", "embedding-summary-model", "embedding-model", "embedding-config", "embedding-provider-error", "chat-summary-model", "chat-config", "chat-cancel", "embedding-cancel")
			for _, variant := range variants {
				collect(scene{"regenerate-" + variant, "regenerate", variant, "/api/v1/chunks/by-id/chunk/questions/regenerate", "invalid ignored JSON"})
			}
		} else {
			for _, operation := range []string{"upsert", "delete"} {
				for _, variant := range variants {
					collect(scene{operation + "-" + variant, operation, variant, "/api/v1/chunks/by-id/chunk/questions", `{"question_id":"old-q","question":"Updated question 中文"}`})
				}
				for _, binder := range []struct{ name, body string }{{"empty", ""}, {"null", "null"}, {"object", "{}"}, {"array", "[]"}, {"incomplete", "{"}, {"null-fields", `{"question_id":null,"question":null}`}, {"new-question", `{"question":"New question 中文"}`}, {"blank-question", `{"question_id":"old-q","question":"  \n "}`}, {"missing-id", `{"question_id":"missing","question":"New question 中文"}`}, {"id-spaces", `{"question_id":" old-q ","question":"Changed"}`}, {"number-id", `{"question_id":4,"question":"Changed"}`}, {"number-question", `{"question_id":"old-q","question":4}`}, {"bool-question", `{"question":true}`}, {"fold", `{"QUESTION_ID":"old-q","QUESTION":"Changed"}`}, {"duplicate-null", `{"question_id":"old-q","question":"Changed","question":null}`}, {"duplicate-id-null", `{"question_id":"old-q","question_id":null,"question":"Changed"}`}, {"duplicate-question", `{"question_id":"old-q","question":"Discard","question":"Changed"}`}, {"type-before-valid", `{"question_id":"old-q","question":4,"question":"Changed"}`}, {"trailing", `{"question_id":"old-q","question":"Changed"}garbage`}, {"unicode-trim", `{"question_id":"old-q","question":"\u3000中文\u00a0"}`}, {"unknown", `{"question_id":"old-q","question":"Changed","unknown":[]}`}} {
					collect(scene{operation + "-bind-" + binder.name, operation, "ok", "/api/v1/chunks/by-id/chunk/questions", binder.body})
				}
			}
		}
	} else {
		variants := []string{"ok", "moving", "moving-copy", "moving-invalid-operation", "missing-kb", "deleted-kb", "foreign-document", "deleted-document", "foreign-chunk", "deleted-chunk", "wrong-document", "wrong-chunk-kb", "creator-viewer", "creator-contributor", "noncreator-contributor", "noncreator-viewer", "noncreator-admin", "shared-editor", "shared-viewer", "shared-revoked", "api-ingest", "api-retrieve", "api-full", "api-wrong-kb", "api-full-wrong-kb", "non-text", "invalid-parent", "foreign-child", "wrong-child-kb", "no-index", "model-missing", "provider-error", "index-error", "revision-error", "parent-error", "existing-source", "index-failed-noop", "image-removed", "image-added", "disabled", "summary-existing", "locators-body", "locators-enabled", "locators-noop", "parent-unicode", "parent-overlap", "parent-offset-invalid", "image-html-retained", "image-original-url", "image-invalid-info", "image-fold-info", "image-restored", "metadata-array", "question-fold", "question-duplicate", "question-long-id", "question-whitespace", "title-spaces", "context-header-spaces", "keyword-only", "vector-only", "wiki-only", "no-index-blob", "vector-only-blob", "keyword-only-blob", "wiki-only-blob", "invalid-index-blob", "summary-refresh", "summary-queue-error", "summary-no-model", "shared-summary-refresh", "edit-during-provider", "disable-during-provider", "metadata-during-provider", "doc-owner-during-provider", "kb-owner-during-provider", "chunk-owner-during-provider"}
		for _, op := range []string{"edit", "revert"} {
			for _, variant := range variants {
				target := "/api/v1/chunks/doc/chunk"
				body := `{"content":"Updated content 中文","expected_revision":3}`
				if op == "revert" {
					target += "/revert"
					body = `{"revision":2,"expected_revision":3}`
				}
				collect(scene{op + "-" + variant, op, variant, target, body})
			}
		}
		for _, v := range []struct{ name, body string }{
			{"empty", ""}, {"null", "null"}, {"object", "{}"}, {"array", "[]"}, {"incomplete", "{"},
			{"no-op", `{"content":"Chunk content chunk"}`}, {"null-fields", `{"content":null,"is_enabled":null,"expected_revision":null}`},
			{"disable", `{"is_enabled":false}`}, {"enable", `{"is_enabled":true}`}, {"wrong-revision", `{"content":"Changed","expected_revision":2}`},
			{"negative-revision", `{"expected_revision":-1}`}, {"number-content", `{"content":4}`}, {"bool-content", `{"content":false}`},
			{"number-enabled", `{"is_enabled":1}`}, {"string-enabled", `{"is_enabled":"false"}`}, {"fraction-revision", `{"expected_revision":3.1}`},
			{"overflow-revision", `{"expected_revision":9223372036854775808}`}, {"blank-content", `{"content":"  \n \t "}`},
			{"fold", `{"CONTENT":"Changed","IS_ENABLED":false,"EXPECTED_REVISION":3}`},
			{"duplicate-null", `{"content":"Changed","content":null}`}, {"duplicate", `{"content":"Discard","content":"Changed"}`},
			{"type-before-valid", `{"content":4,"content":"Changed"}`}, {"trailing", `{"content":"Changed"}garbage`},
			{"unicode-trim", `{"content":"\u3000中文\u00a0"}`}, {"unknown", `{"unused":[true,null]}`},
			{"byte-limit", `{"content":"` + strings.Repeat("x", 200000) + `"}`}, {"byte-over", `{"content":"` + strings.Repeat("x", 200001) + `"}`},
		} {
			collect(scene{"edit-bind-" + v.name, "edit", "ok", "/api/v1/chunks/doc/chunk", v.body})
		}
		for _, v := range []struct{ name, body string }{
			{"empty", ""}, {"null", "null"}, {"object", "{}"}, {"array", "[]"}, {"zero", `{"revision":0}`}, {"negative", `{"revision":-1}`},
			{"null-revision", `{"revision":null}`}, {"number", `{"revision":1.5}`}, {"overflow", `{"revision":9223372036854775808}`},
			{"string", `{"revision":"2"}`}, {"wrong-expected", `{"revision":2,"expected_revision":2}`},
			{"missing", `{"revision":99}`}, {"duplicate-null", `{"revision":2,"revision":null}`}, {"fold", `{"REVISION":2}`},
			{"trailing", `{"revision":2}garbage`},
		} {
			collect(scene{"revert-bind-" + v.name, "revert", "ok", "/api/v1/chunks/doc/chunk/revert", v.body})
		}
	}
	b, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(b, '\n'), 0600))
	fmt.Println("exported", len(out), "actual Go chunk edit/revert HTTP/model/SQL scenes")
}
func run(s scene) any {
	dir, e := os.MkdirTemp("", "weknora-go-chunk-delete-")
	must(e)
	defer os.RemoveAll(dir)
	var dialect gorm.Dialector = sqlite.Open(filepath.Join(dir, "case.db"))
	pgDSN := os.Getenv("WEKNORA_CHUNK_EDIT_PARITY_PG_DSN")
	pg := pgDSN != ""
	if pg {
		u, e := url.Parse(pgDSN)
		must(e)
		if u.Hostname() != "127.0.0.1" || u.Path != "/weknora_chunk_edit_parity" {
			panic("Owned loopback chunk edit DB required")
		}
		dialect = postgres.Open(pgDSN)
	}
	db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	if !pg {
		for _, m := range []string{"000000_init", "000003_knowledge_base_auto_tag_config", "000004_memory", "000007_system_admin_and_settings", "000008_processing_spans_and_pending_subtasks", "000010_knowledge_multi_tags", "000011_principal_model", "000021_knowledge_profiles", "000031_model_catalog_config", "000033_chunk_source_locators"} {
			b, e := os.ReadFile("migrations/sqlite/" + m + ".up.sql")
			must(e)
			must(fixtureExec(db, string(b)).Error)
		}
	} else {
		for _, trigger := range []string{"block_chunk_delete", "block_parent_edit"} {
			must(fixtureExec(db, "DROP TRIGGER IF EXISTS "+trigger+" ON chunks").Error)
		}
		must(fixtureExec(db, "DROP TRIGGER IF EXISTS block_edit_revision ON chunk_revisions").Error)
		must(fixtureExec(db, "DROP TRIGGER IF EXISTS block_edit_index ON embeddings").Error)
		must(fixtureExec(db, "DROP TABLE IF EXISTS chunk_delete_fts").Error)
		must(fixtureExec(db, "TRUNCATE tenants,knowledge_bases,knowledges,chunks,models,embeddings,chunk_revisions,organizations,organization_tenant_members,kb_shares,audit_logs RESTART IDENTITY CASCADE").Error)
		must(fixtureExec(db, "ALTER SEQUENCE chunks_seq_id_seq RESTART WITH 100000000").Error)
	}
	must(fixtureExec(db, "INSERT INTO tenants(id,name,business) VALUES(1,'Owner','test'),(2,'Caller','test')").Error)
	must(fixtureExec(db, "INSERT INTO knowledge_bases(id,tenant_id,creator_id,name,type,embedding_model_id,summary_model_id) VALUES('kb',1,'caller','KB','document','',''),('other-kb',1,'other','Other','document','','')").Error)
	stamp := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, id := range []string{"doc", "other"} {
		must(fixtureExec(db, "INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,created_at,updated_at) VALUES(?,1,'kb','file','Document','manual','completed',?,?)", id, stamp, stamp).Error)
	}
	for i, id := range []string{"chunk", "child", "other", "foreign", "wrong-kb"} {
		tenant := 1
		document, kb, parent, kind := "doc", "kb", "", "text"
		if id == "foreign" {
			tenant = 2
		}
		if id == "other" {
			document = "other"
		}
		if id == "wrong-kb" {
			kb = "other-kb"
		}
		if id == "child" {
			parent = "chunk"
			kind = "image_caption"
		}
		must(fixtureExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,is_enabled,chunk_type,parent_chunk_id,status,content_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,0,20,true,?,?,0,3,?,?)", id, tenant, document, kb, "Chunk content "+id, i, kind, parent, stamp, stamp).Error)
	}
	for i := 1; i <= 2; i++ {
		must(fixtureExec(db, "INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,1,'kb','doc','chunk',?,?,?, 'caller','manual',?,?)", fmt.Sprintf("revision-%d", i), i, fmt.Sprintf("Original %d 中文", i), i == 2, stamp, stamp).Error)
	}
	// A real FTS store is retained by these direct soft-delete APIs.
	must(fixtureExec(db, "CREATE VIRTUAL TABLE chunk_delete_fts USING fts5(chunk_id UNINDEXED,content)").Error)
	must(fixtureExec(db, "INSERT INTO chunk_delete_fts(chunk_id,content) VALUES('chunk','Original physical content'),('child','Image content')").Error)
	metadata := map[string]string{
		"moving": `{"_knowledge_transfer":{"operation":"move","phase":"moving"}}`, "moving-copy": `{"_knowledge_transfer":{"operation":"copy","phase":"moving"}}`, "moving-phase-only": `{"_knowledge_transfer":{"phase":"moving"}}`, "moving-casefold": `{"_knowledge_transfer":{"OPERATION":"move","PHASE":"moving"}}`, "moving-null-operation": `{"_knowledge_transfer":{"operation":null,"phase":"moving"}}`, "moving-duplicate-null": `{"_knowledge_transfer":{"operation":"move","operation":null,"phase":"moving"}}`, "moving-invalid-operation": `{"_knowledge_transfer":{"operation":4,"operation":"move","phase":"moving"}}`, "moving-invalid-metadata": `{"_knowledge_transfer":4}`, "moving-root-null": `null`, "moving-root-array": `[]`, "moving-outer-casefold": `{"_KNOWLEDGE_TRANSFER":{"operation":"move","phase":"moving"}}`}
	if raw, ok := metadata[s.variant]; ok {
		must(fixtureExec(db, "UPDATE knowledges SET metadata=? WHERE id='doc'", []byte(raw)).Error)
	}
	switch s.variant {
	case "missing-kb":
		must(fixtureExec(db, "DELETE FROM knowledge_bases WHERE id='kb'").Error)
	case "deleted-kb":
		must(fixtureExec(db, "UPDATE knowledge_bases SET deleted_at=CURRENT_TIMESTAMP WHERE id='kb'").Error)
	case "foreign-document":
		must(fixtureExec(db, "UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
	case "deleted-document":
		must(fixtureExec(db, "UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'").Error)
	case "foreign-chunk":
		must(fixtureExec(db, "UPDATE chunks SET tenant_id=2 WHERE id='chunk'").Error)
	case "deleted-chunk":
		must(fixtureExec(db, "UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE id='chunk'").Error)
	case "wrong-document":
		must(fixtureExec(db, "UPDATE chunks SET knowledge_id='other' WHERE id='chunk'").Error)
	case "wrong-chunk-kb":
		must(fixtureExec(db, "UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='chunk'").Error)
	case "empty-revisions":
		must(fixtureExec(db, "DELETE FROM chunk_revisions").Error)
	case "revision-table-missing":
		must(fixtureExec(db, "DROP TABLE chunk_revisions").Error)
	case "write-error":
		must(fixtureExec(db, "CREATE TRIGGER block_chunk_delete BEFORE UPDATE OF deleted_at ON chunks BEGIN SELECT RAISE(ABORT,'controlled chunk write failure'); END").Error)
	}
	must(fixtureExec(db, `UPDATE knowledge_bases SET embedding_model_id='embedding',indexing_strategy='{"vector_enabled":true,"keyword_enabled":true,"graph_enabled":false,"wiki_enabled":false}' WHERE id='kb'`).Error)
	must(fixtureExec(db, `UPDATE chunks SET metadata='{"generated_questions":[{"id":"old-q","question":"Old question 中文","content_revision":1}]}',context_header='## Header' WHERE id='chunk'`).Error)
	var index interfaces.RetrieveEngineRepository
	if pg {
		index = pgindex.NewPostgresRetrieveEngineRepository(db)
	} else {
		index = sr.NewSQLiteRetrieveEngineRepository(db)
	}
	infos := []*types.IndexInfo{}
	vectors := map[string][]float32{}
	for _, id := range []string{"chunk", "child", "other", "foreign", "wrong-kb"} {
		document := "doc"
		kb := "kb"
		if id == "other" {
			document = "other"
		}
		if id == "wrong-kb" {
			kb = "other-kb"
		}
		infos = append(infos, &types.IndexInfo{SourceID: id, SourceType: 0, ChunkID: id, KnowledgeID: document, KnowledgeBaseID: kb, Content: "Old physical " + id, IsEnabled: true})
		vectors[id] = fixtureVector("Old physical " + id)
	}
	infos = append(infos, &types.IndexInfo{SourceID: types.GeneratedQuestionSourceID("chunk", "old-q"), SourceType: 0, ChunkID: "chunk", KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "Old physical question", IsEnabled: true})
	vectors[types.GeneratedQuestionSourceID("chunk", "old-q")] = fixtureVector("Old physical question")
	must(index.BatchSave(context.Background(), infos, map[string]any{"embedding": vectors}))
	switch s.variant {
	case "non-text":
		must(fixtureExec(db, "UPDATE chunks SET chunk_type='image_ocr' WHERE id='chunk'").Error)
	case "invalid-parent":
		must(fixtureExec(db, "UPDATE chunks SET parent_chunk_id='absent' WHERE id='chunk'").Error)
	case "foreign-child":
		must(fixtureExec(db, "UPDATE chunks SET knowledge_id='other' WHERE id='child'").Error)
	case "wrong-child-kb":
		must(fixtureExec(db, "UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='child'").Error)
	case "no-index":
		must(fixtureExec(db, `UPDATE knowledge_bases SET indexing_strategy='{"vector_enabled":false,"keyword_enabled":false,"graph_enabled":false,"wiki_enabled":false}' WHERE id='kb'`).Error)
	case "model-missing":
		must(fixtureExec(db, "UPDATE knowledge_bases SET embedding_model_id='absent' WHERE id='kb'").Error)
	case "index-error":
		must(fixtureExec(db, "CREATE TRIGGER block_edit_index BEFORE INSERT ON lite_embeddings BEGIN SELECT RAISE(ABORT,'controlled edit index failure'); END").Error)
	case "revision-error":
		must(fixtureExec(db, "CREATE TRIGGER block_edit_revision BEFORE INSERT ON chunk_revisions BEGIN SELECT RAISE(ABORT,'controlled edit revision failure'); END").Error)
	case "existing-source":
		must(fixtureExec(db, "UPDATE chunks SET source_content='Immutable original content' WHERE id='chunk'").Error)
	case "index-failed-noop":
		must(fixtureExec(db, "UPDATE chunks SET index_status='failed' WHERE id='chunk'").Error)
		if s.operation == "edit" {
			s.body = `{}`
		}
	case "image-removed":
		must(fixtureExec(db, "UPDATE chunks SET content='Body ![img](page.png)' WHERE id='chunk'").Error)
		must(fixtureExec(db, `UPDATE chunks SET image_info='[{"url":"page.png","ocr_text":"OCR"}]' WHERE id='child'`).Error)
	case "image-added":
		if s.operation == "edit" {
			s.body = `{"content":"Body ![new](new.png)"}`
		}
	case "disabled":
		must(fixtureExec(db, "UPDATE chunks SET is_enabled=false WHERE id='chunk'").Error)
	case "summary-existing":
		must(fixtureExec(db, "UPDATE knowledges SET summary_status='completed',description='Previous description' WHERE id='doc'").Error)
	case "parent-error":
		must(fixtureExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,chunk_type) VALUES('parent',1,'doc','kb','Original parent source',50,0,20,'text')").Error)
		must(fixtureExec(db, "UPDATE chunks SET parent_chunk_id='parent' WHERE id='chunk'").Error)
		must(fixtureExec(db, "CREATE TRIGGER block_parent_edit BEFORE UPDATE OF content ON chunks WHEN OLD.id='parent' BEGIN SELECT RAISE(ABORT,'controlled parent edit failure'); END").Error)
	}

	if strings.HasPrefix(s.variant, "locators-") {
		must(fixtureExec(db, `UPDATE chunks SET source_locators='[{"type":"text","source_hash":"original","start":2,"end":8,"quote":"original quote"}]' WHERE id='chunk'`).Error)
		if s.operation == "edit" {
			if s.variant == "locators-enabled" {
				s.body = `{"is_enabled":false}`
			} else if s.variant == "locators-noop" {
				s.body = `{}`
			}
		}
	}
	if strings.HasPrefix(s.variant, "parent-") && s.variant != "parent-error" {
		must(fixtureExec(db, `INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,chunk_type,source_locators,created_at,updated_at) VALUES('parent',1,'doc','kb','前😀甲乙丙丁后abcdefghijklmnop',50,100,123,'text','[{"type":"text","quote":"parent evidence"}]',?,?)`, stamp, stamp).Error)
		must(fixtureExec(db, "UPDATE chunks SET parent_chunk_id='parent',start_at=102,end_at=105 WHERE id='chunk'").Error)
		if s.variant == "parent-offset-invalid" {
			must(fixtureExec(db, "UPDATE chunks SET start_at=200,end_at=205 WHERE id='chunk'").Error)
		}
		if s.variant == "parent-overlap" {
			must(fixtureExec(db, "INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at,chunk_type,parent_chunk_id,content_revision,created_at,updated_at) VALUES('sibling',1,'doc','kb','旧编辑冲突内容 twelve chars more',51,103,107,'text','parent',2,?,?)", stamp, stamp).Error)
		}
	}
	if strings.HasPrefix(s.variant, "image-") && s.variant != "image-added" && s.variant != "image-removed" {
		must(fixtureExec(db, `UPDATE chunks SET content='Body ![img](page.png)',source_content='Body ![img](page.png) <img src="original.png">' WHERE id='chunk'`).Error)
		must(fixtureExec(db, `UPDATE chunks SET image_info='[{"url":"page.png","original_url":"original.png","caption":"Caption"}]' WHERE id='child'`).Error)
		if s.variant == "image-html-retained" && s.operation == "edit" {
			s.body = `{"content":"Changed <IMG alt='x' src = ' page.png '>","expected_revision":3}`
		}
		if s.variant == "image-original-url" && s.operation == "edit" {
			s.body = `{"content":"Changed ![img](original.png)","expected_revision":3}`
		}
		if s.variant == "image-invalid-info" {
			must(fixtureExec(db, `UPDATE chunks SET image_info='[{"url":"page.png","start_pos":"wrong"}]' WHERE id='child'`).Error)
			if s.operation == "edit" {
				s.body = `{"content":"Changed ![img](page.png)"}`
			}
		}
		if s.variant == "image-fold-info" {
			must(fixtureExec(db, `UPDATE chunks SET image_info='[{"URL":"page.png","ORIGINAL_URL":"original.png"}]' WHERE id='child'`).Error)
			if s.operation == "edit" {
				s.body = `{"content":"Changed ![img](page.png)"}`
			}
		}
		if s.variant == "image-restored" {
			must(fixtureExec(db, "UPDATE chunks SET is_enabled=false WHERE id='child'").Error)
			if s.operation == "edit" {
				s.body = `{"content":"Changed ![img](page.png)"}`
			}
		}
	}
	switch s.variant {
	case "metadata-array":
		must(fixtureExec(db, `UPDATE chunks SET metadata='[]' WHERE id='chunk'`).Error)
	case "question-fold":
		must(fixtureExec(db, `UPDATE chunks SET metadata='{"GENERATED_QUESTIONS":[{"ID":"fold","QUESTION":"Case folded question 中文","CONTENT_REVISION":1}]}' WHERE id='chunk'`).Error)
	case "question-duplicate":
		must(fixtureExec(db, `UPDATE chunks SET metadata='{"generated_questions":[{"id":"same","question":"First question 中文"},{"id":"same","question":"Second question 中文"},{"id":"empty","question":"  \n "}]}' WHERE id='chunk'`).Error)
	case "question-long-id":
		must(fixtureExec(db, "UPDATE chunks SET metadata=? WHERE id='chunk'", `{"generated_questions":[{"id":"`+strings.Repeat("长", 40)+`","question":"Long id question 中文"}]}`).Error)
	case "question-whitespace":
		must(fixtureExec(db, `UPDATE chunks SET metadata='{"generated_questions":[{"id":"spaces","question":"  Question 中文  \n"}]}' WHERE id='chunk'`).Error)
	case "title-spaces":
		must(fixtureExec(db, "UPDATE knowledges SET title='  Spaced title  ' WHERE id='doc'").Error)
	case "context-header-spaces":
		must(fixtureExec(db, "UPDATE chunks SET context_header='  Context Header  ' WHERE id='chunk'").Error)
	case "keyword-only":
		must(fixtureExec(db, `UPDATE knowledge_bases SET indexing_strategy='{"keyword_enabled":true}' WHERE id='kb'`).Error)
	case "vector-only":
		must(fixtureExec(db, `UPDATE knowledge_bases SET indexing_strategy='{"vector_enabled":true}' WHERE id='kb'`).Error)
	case "wiki-only":
		must(fixtureExec(db, `UPDATE knowledge_bases SET indexing_strategy='{"wiki_enabled":true}' WHERE id='kb'`).Error)
	}

	if strings.HasSuffix(s.variant, "-blob") {
		strategy := `{"vector_enabled":false,"keyword_enabled":false,"graph_enabled":false,"wiki_enabled":false}`
		switch s.variant {
		case "vector-only-blob":
			strategy = `{"vector_enabled":true}`
		case "keyword-only-blob":
			strategy = `{"keyword_enabled":true}`
		case "wiki-only-blob":
			strategy = `{"wiki_enabled":true}`
		case "invalid-index-blob":
			strategy = `{"vector_enabled":4}`
		}
		must(fixtureExec(db, "UPDATE knowledge_bases SET indexing_strategy=? WHERE id='kb'", []byte(strategy)).Error)
	}

	if s.operation != "regenerate" && strings.Contains(s.variant, "summary-") && s.variant != "summary-existing" {
		must(fixtureExec(db, "UPDATE knowledges SET summary_status='completed',description='Previous description' WHERE id='doc'").Error)
		if s.variant != "summary-no-model" {
			must(fixtureExec(db, "UPDATE knowledge_bases SET summary_model_id='summary' WHERE id='kb'").Error)
		}
	}
	redis, e := miniredis.Run()
	must(e)
	defer redis.Close()
	client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
	defer client.Close()
	captured := &capture{client: client, rows: []any{}, fail: s.variant == "summary-queue-error"}
	var producer interfaces.TaskEnqueuer
	if s.operation != "regenerate" && strings.Contains(s.variant, "summary-") && s.variant != "summary-existing" {
		producer = captured
	}
	var requests = []any{}
	var mutex sync.Mutex
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body any
		must(json.NewDecoder(r.Body).Decode(&body))
		switch s.variant {
		case "edit-during-provider":
			must(fixtureExec(db, "UPDATE chunks SET content='Successor edit',content_revision=5,last_editor_id='successor',index_status='processing' WHERE id='chunk'").Error)
		case "disable-during-provider":
			must(fixtureExec(db, "UPDATE chunks SET is_enabled=false,content_revision=5,last_editor_id='successor',index_status='processing' WHERE id='chunk'").Error)
		case "metadata-during-provider":
			must(fixtureExec(db, `UPDATE chunks SET metadata='{"generated_questions":[{"id":"successor-q","question":"Successor question 中文"}]}' WHERE id='chunk'`).Error)
		case "doc-owner-during-provider":
			must(fixtureExec(db, "UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
		case "kb-owner-during-provider":
			must(fixtureExec(db, "UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
		case "chunk-owner-during-provider":
			must(fixtureExec(db, "UPDATE chunks SET tenant_id=2 WHERE id='chunk'").Error)
		}

		mutex.Lock()
		requests = append(requests, map[string]any{"path": r.URL.Path, "body": body})
		mutex.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if s.variant == "provider-error" {
			w.WriteHeader(400)
			fmt.Fprint(w, `{"error":{"message":"controlled edit embedding failure"}}`)
			return
		}
		if r.URL.Path == "/v1/chat/completions" {
			switch s.variant {
			case "chat-owner-change":
				must(fixtureExec(db, "UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
			case "chat-kb-owner-change":
				must(fixtureExec(db, "UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
			case "chat-chunk-owner-change":
				must(fixtureExec(db, "UPDATE chunks SET tenant_id=2 WHERE id='chunk'").Error)
			case "chat-edit":
				must(fixtureExec(db, "UPDATE chunks SET content='Successor edit',content_revision=5 WHERE id='chunk'").Error)
			case "chat-metadata":
				must(fixtureExec(db, `UPDATE chunks SET metadata='{"generated_questions":[{"id":"successor-q","question":"Successor question 中文"}]}' WHERE id='chunk'`).Error)
			case "chat-title":
				must(fixtureExec(db, "UPDATE knowledges SET title='Successor title' WHERE id='doc'").Error)
			case "chat-embedding-model-change":
				must(fixtureExec(db, "UPDATE knowledge_bases SET embedding_model_id='replacement' WHERE id='kb'").Error)
			case "chat-summary-model":
				must(fixtureExec(db, "UPDATE knowledge_bases SET summary_model_id='replacement' WHERE id='kb'").Error)
			case "chat-config":
				must(fixtureExec(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"question_count":2}`)).Error)
			case "chat-cancel":
				must(fixtureExec(db, "UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'").Error)
			}
			if s.variant == "chat-error" {
				w.WriteHeader(400)
				fmt.Fprint(w, `{"error":{"message":"controlled regeneration chat failure"}}`)
				return
			}
			content := "1. First question 中文\n2. Second question 中文\n* Third question 中文\n4. Fourth question 中文"
			if s.variant == "chat-empty" {
				content = "1. a\n *  \n"
			}
			must(json.NewEncoder(w).Encode(map[string]any{"id": "controlled", "model": "unknown", "choices": []any{map[string]any{"index": 0, "message": map[string]any{"role": "assistant", "content": content}, "finish_reason": "stop"}}}))
			return
		}
		switch s.variant {
		case "embedding-edit":
			must(fixtureExec(db, "UPDATE chunks SET content='Embedding successor',content_revision=5 WHERE id='chunk'").Error)
		case "embedding-disable":
			must(fixtureExec(db, "UPDATE chunks SET is_enabled=false,content_revision=5 WHERE id='chunk'").Error)
		case "embedding-metadata":
			must(fixtureExec(db, `UPDATE chunks SET metadata='{"generated_questions":[{"id":"successor-q","question":"Successor question 中文"}]}' WHERE id='chunk'`).Error)
		case "embedding-doc-owner":
			must(fixtureExec(db, "UPDATE knowledges SET tenant_id=2 WHERE id='doc'").Error)
		case "embedding-kb-owner":
			must(fixtureExec(db, "UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'").Error)
		case "embedding-chunk-owner":
			must(fixtureExec(db, "UPDATE chunks SET tenant_id=2 WHERE id='chunk'").Error)
		case "embedding-title":
			must(fixtureExec(db, "UPDATE knowledges SET title='Embedding successor title' WHERE id='doc'").Error)
		case "embedding-summary-model":
			must(fixtureExec(db, "UPDATE knowledge_bases SET summary_model_id='replacement' WHERE id='kb'").Error)
		case "embedding-model":
			must(fixtureExec(db, "UPDATE knowledge_bases SET embedding_model_id='replacement' WHERE id='kb'").Error)
		case "embedding-config":
			must(fixtureExec(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"question_count":2}`)).Error)
		case "embedding-cancel":
			must(fixtureExec(db, "UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'").Error)
		case "embedding-provider-error":
			w.WriteHeader(400)
			fmt.Fprint(w, `{"error":{"message":"controlled edit embedding failure"}}`)
			return
		}
		inputs := body.(map[string]any)["input"].([]any)
		data := []any{}
		for i := len(inputs) - 1; i >= 0; i-- {
			data = append(data, map[string]any{"index": i, "embedding": fixtureVector(inputs[i].(string))})
		}
		must(json.NewEncoder(w).Encode(map[string]any{"data": data}))
	}))
	defer server.Close()
	mr := repo.NewModelRepository(db)
	must(mr.Create(context.Background(), &types.Model{ID: "embedding", TenantID: 1, Name: "unknown", Type: types.ModelTypeEmbedding, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{Provider: "generic", BaseURL: server.URL + "/v1", APIKey: "controlled-key", EmbeddingParameters: types.EmbeddingParameters{Dimension: 3}}}))
	pool, e := ants.NewPool(2)
	must(e)
	defer pool.Release()
	models := svc.NewModelService(mr, repo.NewKnowledgeBaseRepository(db), nil, nil, embedding.NewBatchEmbedder(pool), nil)
	registry := retrieve.NewRetrieveEngineRegistry(nil, nil)
	engineType := types.SQLiteRetrieverEngineType
	if pg {
		engineType = types.PostgresRetrieverEngineType
	}
	must(registry.Register(retrieve.NewKVHybridRetrieveEngine(index, engineType)))
	audit := svc.NewAuditLogService(repo.NewAuditLogRepository(db))
	shares := svc.NewKBShareService(repo.NewKBShareRepository(db), repo.NewOrganizationRepository(db), nil, nil, nil, audit)
	agents := svc.NewAgentShareService(repo.NewAgentShareRepository(db), repo.NewTenantDisabledSharedAgentRepository(db), repo.NewOrganizationRepository(db), repo.NewCustomAgentRepository(db), nil, nil, nil)
	br, kr, cr := repo.NewKnowledgeBaseRepository(db), repo.NewKnowledgeRepository(db), repo.NewChunkRepository(db)
	kbs := svc.NewKnowledgeBaseService(br, kr, cr, nil, shares, nil, nil, nil, repo.NewTenantRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
	serviceConfig := &config.Config{}
	if s.operation == "regenerate" {
		var prompt struct {
			Prompt string `json:"prompt"`
		}
		b, e := os.ReadFile("backend-java/src/main/resources/question-generation-prompt.json")
		must(e)
		must(json.Unmarshal(b, &prompt))
		serviceConfig.Conversation = &config.ConversationConfig{GenerateQuestionsPrompt: prompt.Prompt}
		if s.variant == "prompt-missing" {
			serviceConfig.Conversation.GenerateQuestionsPrompt = "  "
		}
		if s.variant != "no-summary-model" {
			must(fixtureExec(db, "UPDATE knowledge_bases SET summary_model_id='chat' WHERE id='kb'").Error)
		}
		if s.variant != "chat-model-missing" {
			must(mr.Create(context.Background(), &types.Model{ID: "chat", TenantID: 1, Name: "unknown", Type: types.ModelTypeKnowledgeQA, Source: types.ModelSourceRemote, Status: types.ModelStatusActive, Parameters: types.ModelParameters{Provider: "generic", BaseURL: server.URL + "/v1", APIKey: "controlled-key"}}))
		}
		switch s.variant {
		case "empty-content":
			must(fixtureExec(db, "UPDATE chunks SET content='' WHERE id='chunk'").Error)
		case "count-one":
			must(fixtureExec(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"question_count":1}`)).Error)
		case "count-max":
			must(fixtureExec(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"question_count":25}`)).Error)
		case "count-negative":
			must(fixtureExec(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"question_count":-2}`)).Error)
		case "overrides-count":
			must(fixtureExec(db, `UPDATE knowledges SET metadata=? WHERE id='doc'`, []byte(`{"process_overrides":{"question_generation_config":{"question_count":2}}}`)).Error)
		case "custom-instructions":
			must(fixtureExec(db, "UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", []byte(`{"question_count":2,"custom_instructions":"Only objective questions 中文"}`)).Error)
		}
		if strings.HasPrefix(s.variant, "neighbor-") {
			must(fixtureExec(db, "UPDATE chunks SET pre_chunk_id='other',next_chunk_id='foreign' WHERE id='chunk'").Error)
			must(fixtureExec(db, "UPDATE chunks SET knowledge_id='doc',tenant_id=1 WHERE id IN ('other','foreign')").Error)
			switch s.variant {
			case "neighbor-foreign-doc":
				must(fixtureExec(db, "UPDATE chunks SET knowledge_id='other' WHERE id='other'").Error)
			case "neighbor-foreign-owner":
				must(fixtureExec(db, "UPDATE chunks SET tenant_id=2 WHERE id='other'").Error)
			case "neighbor-foreign-kb":
				must(fixtureExec(db, "UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='other'").Error)
			}
		}
	}
	kg, e := svc.NewKnowledgeService(serviceConfig, kr, nil, kbs, nil, nil, nil, cr, nil, nil, nil, nil, nil, models, nil, nil, nil, registry, nil, nil, shares, nil, nil, nil, nil, nil, audit)
	must(e)
	chunks := svc.NewChunkService(cr, kr, br, models, registry, nil, producer, nil)
	h := handler.NewChunkHandler(chunks, kg)
	enabled := true
	cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
	role, user, tenant := types.TenantRoleAdmin, "caller", uint64(1)
	if strings.HasSuffix(s.variant, "viewer") {
		role = types.TenantRoleViewer
	}
	if strings.HasSuffix(s.variant, "contributor") {
		role = types.TenantRoleContributor
	}
	if strings.HasPrefix(s.variant, "noncreator-") {
		user = "intruder"
	}
	if s.variant == "unknown-role" {
		role = types.TenantRole("unknown")
		user = "intruder"
	}
	if strings.HasPrefix(s.variant, "shared-") {
		tenant = 2
		permission := "editor"
		if s.variant == "shared-viewer" {
			permission = "viewer"
		}
		must(fixtureExec(db, "INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'caller')").Error)
		must(fixtureExec(db, "INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('member','org',2,'editor')").Error)
		must(fixtureExec(db, "INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,?,'caller')", permission).Error)
		if s.variant == "shared-revoked" {
			must(fixtureExec(db, "UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP").Error)
		}
	}
	machine := strings.HasPrefix(s.variant, "api-")
	scope := types.TenantAPIKeyScope{Capabilities: types.StringArray{"ingest"}, KnowledgeBaseIDs: []string{"kb"}}
	if s.variant == "api-retrieve" {
		scope.Capabilities = types.StringArray{"retrieve"}
	}
	if strings.HasPrefix(s.variant, "api-full") {
		scope.FullAccess = true
	}
	if strings.HasSuffix(s.variant, "wrong-kb") {
		scope.KnowledgeBaseIDs = []string{"other-kb"}
	}
	router := gin.New()
	router.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
	router.Use(func(c *gin.Context) {
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		ctx = context.WithValue(ctx, types.UserIDContextKey, user)
		callerInfo, err := repo.NewTenantRepository(db).GetTenantByID(ctx, tenant)
		must(err)
		ctx = context.WithValue(ctx, types.TenantInfoContextKey, callerInfo)
		ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
		if machine {
			ctx = types.WithTenantAPIKeyScope(ctx, scope)
		}
		c.Request = c.Request.WithContext(ctx)
		c.Set(types.TenantIDContextKey.String(), tenant)
		c.Set(types.UserIDContextKey.String(), user)
		c.Next()
	})
	gate := middleware.NewAPIKeyRouteAuthorizer()
	writePolicy := middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityIngest)
	readPolicy := middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityRetrieve)
	_ = readPolicy
	gate.Register("PUT", "/api/v1/chunks/:knowledge_id/:id", writePolicy)
	gate.Register("POST", "/api/v1/chunks/:knowledge_id/:id/revert", writePolicy)
	own := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromKnowledgeIDParam, cfg)
	write := middleware.RequireKBAccess(middleware.KBIDFromKnowledgeIDParam("knowledge_id", kg), types.OrgRoleEditor, kbs, shares, agents, cfg)
	router.PUT("/api/v1/chunks/:knowledge_id/:id", gate.Middleware(), own, write, h.UpdateChunk)
	router.POST("/api/v1/chunks/:knowledge_id/:id/revert", gate.Middleware(), own, write, h.RevertChunk)
	questionOwn := middleware.RequireOwnershipOrRole(types.TenantRoleAdmin, h.KBCreatorLookupFromChunkIDParam, cfg)
	questionWrite := middleware.RequireKBAccess(middleware.KBIDFromChunkIDParam("id", chunks), types.OrgRoleEditor, kbs, shares, agents, cfg)
	gate.Register("PUT", "/api/v1/chunks/by-id/:id/questions", writePolicy)
	gate.Register("DELETE", "/api/v1/chunks/by-id/:id/questions", writePolicy)
	gate.Register("POST", "/api/v1/chunks/by-id/:id/questions/regenerate", writePolicy)
	router.PUT("/api/v1/chunks/by-id/:id/questions", gate.Middleware(), questionOwn, questionWrite, h.UpsertGeneratedQuestion)
	router.DELETE("/api/v1/chunks/by-id/:id/questions", gate.Middleware(), questionOwn, questionWrite, h.DeleteGeneratedQuestion)
	router.POST("/api/v1/chunks/by-id/:id/questions/regenerate", gate.Middleware(), questionOwn, questionWrite, h.RegenerateGeneratedQuestions)
	method := "PUT"
	if s.operation == "revert" || s.operation == "regenerate" {
		method = "POST"
	}
	if s.operation == "delete" {
		method = "DELETE"
	}
	r := httptest.NewRequest(method, s.target, strings.NewReader(s.body))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	var body any
	dec := json.NewDecoder(bytes.NewReader(w.Body.Bytes()))
	dec.UseNumber()
	must(dec.Decode(&body))
	var rows, fts []map[string]any
	selection := "id,tenant_id,knowledge_base_id,knowledge_id,content,source_content,status,content_revision,index_status,last_editor_id,is_enabled,parent_chunk_id,metadata,source_locators,CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END AS deleted"
	if pg {
		selection = strings.ReplaceAll(selection, "parent_chunk_id,metadata,source_locators,", "parent_chunk_id,CAST(metadata AS TEXT) AS metadata,CAST(source_locators AS TEXT) AS source_locators,")
	}
	must(db.Table("chunks").Select(selection).Order("id").Find(&rows).Error)
	must(db.Table("chunk_delete_fts").Select("chunk_id,content").Order("chunk_id").Find(&fts).Error)
	var revisions []*types.ChunkRevision
	must(db.Order("revision DESC").Find(&revisions).Error)
	var physical []map[string]any
	indexTable := "lite_embeddings"
	if pg {
		indexTable = "embeddings"
	}
	must(db.Table(indexTable).Select("source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled").Order("source_id").Find(&physical).Error)
	rawVectors := []map[string]any{}
	if !pg {
		must(db.Raw("SELECT e.source_id,e.source_type,v.embedding AS vector FROM lite_embeddings e JOIN vec_embeddings_3 v ON e.id=v.rowid ORDER BY e.source_id,e.source_type").Scan(&rawVectors).Error)
		for _, row := range rawVectors {
			rawVector := row["vector"]
			if wrapped, ok := rawVector.(*interface{}); ok {
				rawVector = *wrapped
			}
			b, ok := rawVector.([]byte)
			if !ok {
				panic(fmt.Sprintf("unexpected vector type %T", rawVector))
			}
			bits := []uint32{}
			for i := 0; i < len(b); i += 4 {
				bits = append(bits, binary.LittleEndian.Uint32(b[i:i+4]))
			}
			row["vector"] = bits
		}
	} else {
		var vectors []struct {
			SourceID   string
			SourceType int
			Vector     string
		}
		must(db.Raw("SELECT source_id,source_type,embedding::text AS vector FROM embeddings ORDER BY source_id,source_type").Scan(&vectors).Error)
		for _, v := range vectors {
			var floats []float32
			must(json.Unmarshal([]byte(v.Vector), &floats))
			bits := []uint32{}
			for _, f := range floats {
				bits = append(bits, math.Float32bits(f))
			}
			rawVectors = append(rawVectors, map[string]any{"source_id": v.SourceID, "source_type": v.SourceType, "vector": bits})
		}
	}
	var counts struct {
		Vectors  int64
		Keywords int64
	}
	if !pg {
		must(db.Table("vec_embeddings_3").Count(&counts.Vectors).Error)
		must(db.Table("lite_embeddings_fts").Count(&counts.Keywords).Error)
	} else {
		must(db.Table("embeddings").Where("dimension=3").Count(&counts.Vectors).Error)
		must(db.Table("embeddings").Count(&counts.Keywords).Error)
	}

	var documents []map[string]any
	must(db.Table("knowledges").Select("id,tenant_id,knowledge_base_id,parse_status,description,summary_status").Order("id").Find(&documents).Error)
	return map[string]any{"tasks": captured.rows, "raw_vectors": rawVectors, "requests": requests, "physical_indexes": physical, "counts": counts, "revisions": revisions, "documents": documents, "request": s.body, "name": s.name, "operation": s.operation, "variant": s.variant, "method": method, "target": s.target, "status": w.Code, "body": body, "chunks": rows, "physical_fts": fts, "caller_tenant": tenant, "user": user, "role": role, "effective_role": types.TenantRoleFromContext(context.WithValue(context.Background(), types.TenantRoleContextKey, role)), "machine": machine, "scope": scope}
}

func fixtureVector(text string) []float32 {
	h := fnv.New32a()
	_, _ = h.Write([]byte(text))
	v := h.Sum32()
	return []float32{float32(v%997+1) / 1000, float32((v/997)%991+1) / 1000, -float32((v/988027)%983+1) / 1000}
}

// SQL adaptation belongs only to this owned oracle database; production Go repositories are unchanged.
func fixtureExec(db *gorm.DB, sql string, args ...any) *gorm.DB {
	if db.Dialector.Name() != "postgres" {
		return db.Exec(sql, args...)
	}
	if strings.HasPrefix(sql, "CREATE VIRTUAL TABLE chunk_delete_fts") {
		return db.Exec("CREATE TABLE chunk_delete_fts(chunk_id TEXT,content TEXT,search TSVECTOR GENERATED ALWAYS AS(to_tsvector('simple',content)) STORED)")
	}
	triggers := map[string]struct{ table, event, message string }{"block_chunk_delete": {"chunks", "UPDATE OF deleted_at", "controlled chunk write failure"}, "block_edit_index": {"embeddings", "INSERT", "controlled edit index failure"}, "block_edit_revision": {"chunk_revisions", "INSERT", "controlled edit revision failure"}, "block_parent_edit": {"chunks", "UPDATE OF content", "controlled parent edit failure"}}
	for name, trigger := range triggers {
		if strings.HasPrefix(sql, "CREATE TRIGGER "+name+" ") {
			r := db.Exec("CREATE OR REPLACE FUNCTION " + name + "_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '" + trigger.message + "'; END $$")
			if r.Error != nil {
				return r
			}
			condition := ""
			if name == "block_parent_edit" {
				condition = " WHEN (OLD.id='parent')"
			}
			return db.Exec("CREATE TRIGGER " + name + " BEFORE " + trigger.event + " ON " + trigger.table + " FOR EACH ROW" + condition + " EXECUTE FUNCTION " + name + "_fn()")
		}
	}
	if strings.Contains(sql, "metadata=") || strings.Contains(sql, "indexing_strategy=") {
		for i, a := range args {
			if b, ok := a.([]byte); ok {
				args[i] = string(b)
			}
		}
	}
	return db.Exec(sql, args...)
}
