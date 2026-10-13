//go:build ignore

package main

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	log "github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

type lookup struct {
	interfaces.KnowledgeBaseService
	kb      *types.KnowledgeBase
	failure bool
}

func (s lookup) GetKnowledgeBaseByID(ctx context.Context, id string) (*types.KnowledgeBase, error) {
	if s.failure {
		return nil, errors.New("isolated KB lookup failure")
	}
	if s.kb == nil {
		return nil, repository.ErrKnowledgeBaseNotFound
	}
	return s.KnowledgeBaseService.GetKnowledgeBaseByID(ctx, id)
}
func main() {
	if err := types.LoadBuiltinAgentsConfig("config"); err != nil {
		panic(err)
	}
	log.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	time.Local = time.UTC
	os.Setenv("RETRIEVE_DRIVER", "postgres")
	out := map[string]any{}
	for _, endpoint := range []string{"knowledge-list", "knowledge-detail", "chunk-list", "chunk-detail"} {
		for _, mode := range []string{"query-edge-time-0", "query-edge-time-1", "query-edge-time-2", "query-edge-time-3", "query-edge-time-4", "query-edge-time-5", "query-edge-time-6", "query-edge-time-7", "query-edge-time-8", "query-edge-time-9", "query-edge-time-10", "query-edge-time-11", "query-edge-time-12", "query-edge-time-13", "query-edge-time-14", "query-edge-time-15", "query-edge-time-16", "query-edge-time-17", "query-edge-time-18", "query-edge-time-19", "query-edge-time-20", "query-edge-time-21", "query-edge-time-22", "query-edge-time-23", "query-edge-time-24", "query-edge-time-25", "query-edge-time-26", "query-edge-time-27", "query-edge-time-28", "query-edge-time-29", "query-edge-time-30", "query-edge-time-31", "query-edge-time-32", "query-edge-time-33", "query-edge-time-34", "query-edge-time-35", "query-edge-time-36", "query-edge-time-37", "query-edge-time-38", "query-edge-time-39", "query-edge-time-40", "query-edge-time-41", "query-edge-time-42", "query-edge-time-43", "query-edge-time-44", "query-edge-time-45", "query-edge-time-46", "query-edge-time-47", "query-edge-time-48", "query-edge-time-49", "query-edge-time-50", "query-edge-time-51", "query-edge-time-52", "query-edge-time-53", "query-edge-time-54", "query-edge-time-55", "query-edge-time-56", "query-edge-time-57", "query-edge-time-58", "query-edge-time-59", "query-edge-time-60", "query-edge-time-61", "query-edge-time-62", "query-edge-time-63", "query-edge-time-64", "query-edge-time-65", "query-edge-time-66", "query-edge-time-67", "query-edge-time-68", "query-edge-time-69", "query-edge-time-70", "query-edge-time-71", "query-edge-time-72", "query-edge-time-73", "query-edge-time-74", "raw-metadata-empty", "raw-metadata-null", "raw-metadata-space", "raw-metadata-object", "raw-metadata-duplicate", "raw-metadata-array", "raw-metadata-string", "raw-metadata-boolean", "raw-metadata-zero", "raw-metadata-huge-float", "raw-metadata-exact-integer", "raw-metadata-fraction", "raw-metadata-trailing-value", "raw-metadata-trailing-garbage", "raw-metadata-bad-array", "raw-metadata-bad-number", "raw-metadata-empty-object", "raw-custom_metadata-empty", "raw-custom_metadata-null", "raw-custom_metadata-space", "raw-custom_metadata-object", "raw-custom_metadata-duplicate", "raw-custom_metadata-array", "raw-custom_metadata-string", "raw-custom_metadata-boolean", "raw-custom_metadata-zero", "raw-custom_metadata-huge-float", "raw-custom_metadata-exact-integer", "raw-custom_metadata-fraction", "raw-custom_metadata-trailing-value", "raw-custom_metadata-trailing-garbage", "raw-custom_metadata-bad-array", "raw-custom_metadata-bad-number", "raw-custom_metadata-empty-object", "raw-last_faq_import_result-empty", "raw-last_faq_import_result-null", "raw-last_faq_import_result-space", "raw-last_faq_import_result-object", "raw-last_faq_import_result-duplicate", "raw-last_faq_import_result-array", "raw-last_faq_import_result-string", "raw-last_faq_import_result-boolean", "raw-last_faq_import_result-zero", "raw-last_faq_import_result-huge-float", "raw-last_faq_import_result-exact-integer", "raw-last_faq_import_result-fraction", "raw-last_faq_import_result-trailing-value", "raw-last_faq_import_result-trailing-garbage", "raw-last_faq_import_result-bad-array", "raw-last_faq_import_result-bad-number", "raw-last_faq_import_result-empty-object", "raw-relation_chunks-empty", "raw-relation_chunks-null", "raw-relation_chunks-space", "raw-relation_chunks-object", "raw-relation_chunks-duplicate", "raw-relation_chunks-array", "raw-relation_chunks-string", "raw-relation_chunks-boolean", "raw-relation_chunks-zero", "raw-relation_chunks-huge-float", "raw-relation_chunks-exact-integer", "raw-relation_chunks-fraction", "raw-relation_chunks-trailing-value", "raw-relation_chunks-trailing-garbage", "raw-relation_chunks-bad-array", "raw-relation_chunks-bad-number", "raw-relation_chunks-empty-object", "raw-indirect_relation_chunks-empty", "raw-indirect_relation_chunks-null", "raw-indirect_relation_chunks-space", "raw-indirect_relation_chunks-object", "raw-indirect_relation_chunks-duplicate", "raw-indirect_relation_chunks-array", "raw-indirect_relation_chunks-string", "raw-indirect_relation_chunks-boolean", "raw-indirect_relation_chunks-zero", "raw-indirect_relation_chunks-huge-float", "raw-indirect_relation_chunks-exact-integer", "raw-indirect_relation_chunks-fraction", "raw-indirect_relation_chunks-trailing-value", "raw-indirect_relation_chunks-trailing-garbage", "raw-indirect_relation_chunks-bad-array", "raw-indirect_relation_chunks-bad-number", "raw-indirect_relation_chunks-empty-object"} {
			db, e := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
			if e != nil {
				panic(e)
			}
			if e = db.AutoMigrate(&types.AuditLog{}, &types.Organization{}, &types.OrganizationTenantMember{}, &types.KnowledgeBaseShare{}, &types.AgentShare{}, &types.CustomAgent{}, &types.TenantDisabledSharedAgent{}, &types.VectorStore{}, &types.KnowledgeTag{}, &types.KnowledgeTagRelation{}); e != nil {
				panic(e)
			}
			rawSchema, err := os.ReadFile("migrations/sqlite/000000_init.up.sql")
			if err != nil {
				panic(err)
			}
			for _, table := range []string{"knowledges", "chunks"} {
				statement := regexp.MustCompile("(?s)CREATE TABLE IF NOT EXISTS " + table + " \\(.*?\\);").FindString(string(rawSchema))
				if statement == "" {
					panic(table + " schema not found")
				}
				if err := db.Exec(statement).Error; err != nil {
					panic(err)
				}
			}
			for _, script := range []string{"000002_knowledge_folder_path", "000008_processing_spans_and_pending_subtasks", "000021_knowledge_profiles", "000033_chunk_source_locators"} {
				raw, err := os.ReadFile("migrations/sqlite/" + script + ".up.sql")
				if err != nil {
					panic(err)
				}
				for _, statement := range strings.Split(string(raw), ";") {
					at := strings.Index(statement, "ALTER TABLE knowledges")
					if at < 0 {
						at = strings.Index(statement, "ALTER TABLE chunks")
					}
					if at >= 0 {
						if err := db.Exec(statement[at:]).Error; err != nil {
							panic(err)
						}
					}
				}
			}
			db.Callback().Create().After("gorm:create").Register("oracle:create-check", func(d *gorm.DB) {
				if d.Error != nil {
					panic(d.Error)
				}
			})
			role := types.TenantRoleViewer
			creator := "caller"
			owner := uint64(7)
			enabled := true
			cross := false
			system := false
			machine := false
			scope := types.TenantAPIKeyScope{KeyID: 1, Capabilities: types.StringArray{"retrieve"}}
			method := "GET"
			template := "/api/v1/knowledge-bases/:id"
			raw := "/api/v1/knowledge-bases/kb"
			switch mode {
			case "viewer-other", "system-admin-viewer", "rbac-off-own":
				creator = "other"
			case "contributor-other":
				role = types.TenantRoleContributor
				creator = "other"
			case "contributor-creator":
				role = types.TenantRoleContributor
			case "admin-other", "missing-admin", "lookup-failure-admin":
				role = types.TenantRoleAdmin
				creator = "other"
			case "owner-blank":
				role = types.TenantRoleOwner
				creator = ""
			case "viewer-blank":
				creator = ""
			}
			if mode == "system-admin-viewer" {
				system = true
			}
			if mode == "cross-superuser-own" || mode == "cross-superuser-foreign" {
				cross = true
			}
			if mode == "rbac-off-own" || mode == "rbac-off-foreign" || mode == "rbac-off-tenant-viewer" {
				enabled = false
			}
			if len(mode) >= 7 && mode[:7] == "api-key" {
				machine = true
				creator = "other"
			}
			if mode == "api-key-full" {
				scope.FullAccess = true
				scope.Capabilities = nil
			}
			if mode == "api-key-ingest" {
				scope.Capabilities = types.StringArray{"ingest"}
			}
			if mode == "api-key-scope" {
				scope.KnowledgeBaseIDs = types.StringArray{"other"}
			}
			if mode == "api-key-update" {
				scope.Capabilities = types.StringArray{"manage_kbs"}
				method = "PUT"
				template = "/api/v1/knowledge-bases/:id"
				raw = "/api/v1/knowledge-bases/kb"
			}
			shared := false
			switch mode {
			case "foreign-no-share", "cross-superuser-foreign", "rbac-off-foreign":
				owner = 8
				role = types.TenantRoleContributor
			default:
				if len(mode) >= 6 && mode[:6] == "share-" || mode == "member-viewer" || mode == "tenant-viewer" || mode == "rbac-off-tenant-viewer" || mode == "source-gone" || mode == "target-gone" || mode == "org-deleted" || mode == "multiple-shares" || mode == "empty-share-permission" || mode == "empty-member-role" || mode == "api-key-shared-editor" {
					owner = 8
					shared = true
					role = types.TenantRoleContributor
				}
			}
			if mode == "tenant-viewer" || mode == "rbac-off-tenant-viewer" {
				role = types.TenantRoleViewer
			}
			if mode == "org-invalid-source-ignored" || mode == "shared-unbound" || mode == "shared-faq" || mode == "shared-count-failure" {
				owner = 8
				shared = true
				role = types.TenantRoleViewer
			}
			if shared {
				permission := "editor"
				member := "editor"
				if mode == "share-admin" {
					permission = "admin"
					member = "admin"
				}
				if mode == "share-viewer" {
					permission = "viewer"
				}
				if mode == "member-viewer" {
					member = "viewer"
				}
				if mode == "empty-share-permission" {
					permission = ""
				}
				if mode == "empty-member-role" {
					member = ""
				}
				db.Create(&types.Organization{ID: "org", Name: "Org", OwnerTenantID: 8, InviteCode: "code1"})
				source := uint64(8)
				if mode == "share-foreign-source-member" {
					source = 9
				}
				db.Create(&types.OrganizationTenantMember{ID: "source", OrganizationID: "org", TenantID: source, Role: types.OrgRoleAdmin})
				db.Create(&types.OrganizationTenantMember{ID: "target", OrganizationID: "org", TenantID: 7, Role: types.OrgMemberRole(member)})
				db.Create(&types.KnowledgeBaseShare{ID: "share", KnowledgeBaseID: "kb", OrganizationID: "org", SourceTenantID: source, Permission: types.OrgMemberRole(permission)})
				if mode == "empty-share-permission" {
					db.Model(&types.KnowledgeBaseShare{}).Where("id = ?", "share").Update("permission", "")
				}
				if mode == "empty-member-role" {
					db.Model(&types.OrganizationTenantMember{}).Where("id = ?", "target").Update("role", "")
				}
				if mode == "source-gone" {
					db.Where("id = ?", "source").Delete(&types.OrganizationTenantMember{})
				}
				if mode == "target-gone" {
					db.Where("id = ?", "target").Delete(&types.OrganizationTenantMember{})
				}
				if mode == "org-deleted" {
					db.Where("id = ?", "org").Delete(&types.Organization{})
				}
				if mode == "share-deleted" {
					db.Where("id = ?", "share").Delete(&types.KnowledgeBaseShare{})
				}
				if mode == "multiple-shares" {
					db.Model(&types.KnowledgeBaseShare{}).Where("id = ?", "share").Update("permission", "viewer")
					db.Create(&types.Organization{ID: "org2", Name: "Second", OwnerTenantID: 8, InviteCode: "code2"})
					db.Create(&types.OrganizationTenantMember{ID: "source2", OrganizationID: "org2", TenantID: 8, Role: types.OrgRoleViewer})
					db.Create(&types.OrganizationTenantMember{ID: "target2", OrganizationID: "org2", TenantID: 7, Role: types.OrgRoleAdmin})
					db.Create(&types.KnowledgeBaseShare{ID: "share2", KnowledgeBaseID: "kb", OrganizationID: "org2", SourceTenantID: 8, Permission: types.OrgRoleEditor})
				}
				if mode == "share-sql-failure" || mode == "share-sql-failure-agent-fallback" {
					db.Migrator().DropTable(&types.KnowledgeBaseShare{})
				}
			}
			agentID := ""
			sourceSelector := ""
			hasAgent := len(mode) >= 6 && mode[:6] == "agent-" || mode == "share-sql-failure-agent-fallback"
			if hasAgent {
				owner = 8
				role = types.TenantRoleViewer
				if !shared {
					db.Create(&types.Organization{ID: "org", Name: "Org", OwnerTenantID: 8, InviteCode: "code1"})
					db.Create(&types.OrganizationTenantMember{ID: "source", OrganizationID: "org", TenantID: 8, Role: types.OrgRoleViewer})
					db.Create(&types.OrganizationTenantMember{ID: "target", OrganizationID: "org", TenantID: 7, Role: types.OrgRoleViewer})
				}
				selection := "all"
				ids := []string{"kb"}
				if mode == "agent-selected-kb" || mode == "agent-selected-other" {
					selection = "selected"
				}
				if mode == "agent-selected-other" {
					ids = []string{"other"}
				}
				if mode == "agent-none" {
					selection = "none"
				}
				if mode == "agent-unknown-mode" {
					selection = "unknown"
				}
				agentTenant := uint64(8)
				if mode == "agent-wrong-owner" {
					agentTenant = 9
					db.Create(&types.OrganizationTenantMember{ID: "source9", OrganizationID: "org", TenantID: 9, Role: types.OrgRoleViewer})
				}
				id := "agent"
				if mode == "agent-builtin" {
					id = "builtin-quick-answer"
				}
				db.Create(&types.CustomAgent{ID: id, TenantID: agentTenant, Name: "Agent", IsBuiltin: mode == "agent-is-builtin", Config: types.CustomAgentConfig{KBSelectionMode: selection, KnowledgeBases: ids}})
				db.Create(&types.AgentShare{ID: "share-agent", AgentID: id, OrganizationID: "org", SourceTenantID: agentTenant, Permission: types.OrgRoleViewer})
				if mode != "agent-implicit-all" && mode != "agent-implicit-multiple" && mode != "agent-disabled" && mode != "share-sql-failure-agent-fallback" {
					agentID = id
				}
				if mode == "agent-source-gone" {
					db.Where("id=?", "source").Delete(&types.OrganizationTenantMember{})
				}
				if mode == "agent-target-gone" {
					db.Where("id=?", "target").Delete(&types.OrganizationTenantMember{})
				}
				if mode == "agent-org-deleted" {
					db.Where("id=?", "org").Delete(&types.Organization{})
				}
				if mode == "agent-share-deleted" {
					db.Where("id=?", "share-agent").Delete(&types.AgentShare{})
				}
				if mode == "agent-deleted" {
					db.Where("id=?", "agent").Delete(&types.CustomAgent{})
				}
				if mode == "agent-disabled" {
					db.Create(&types.TenantDisabledSharedAgent{TenantID: 7, AgentID: "agent", SourceTenantID: 8})
				}
				if mode == "agent-sql-failure" {
					db.Migrator().DropTable(&types.AgentShare{})
				}
				if mode == "agent-ambiguous-source" || mode == "agent-explicit-correct-source" || mode == "agent-explicit-wrong-source" || mode == "agent-implicit-multiple" {
					db.Create(&types.OrganizationTenantMember{ID: "source9", OrganizationID: "org", TenantID: 9, Role: types.OrgRoleViewer})
					db.Create(&types.CustomAgent{ID: "agent", TenantID: 9, Name: "Wrong owner", Config: types.CustomAgentConfig{KBSelectionMode: "all"}})
					db.Create(&types.AgentShare{ID: "first-lexicographic", AgentID: "agent", OrganizationID: "org", SourceTenantID: 9, Permission: types.OrgRoleViewer})
				}
				if mode == "agent-config-invalid-json" {
					db.Model(&types.CustomAgent{}).Where("id=?", "agent").Update("config", "{")
				}
				if mode == "agent-config-wrong-type" {
					db.Model(&types.CustomAgent{}).Where("id=?", "agent").Update("config", `{"kb_selection_mode":1}`)
				}
				if mode == "agent-config-object-list" {
					db.Model(&types.CustomAgent{}).Where("id=?", "agent").Update("config", `{"kb_selection_mode":"selected","knowledge_bases":{"x":"kb"}}`)
				}
				if mode == "agent-config-invalid-list-even-all" {
					db.Model(&types.CustomAgent{}).Where("id=?", "agent").Update("config", `{"kb_selection_mode":"all","knowledge_bases":{"x":"kb"}}`)
				}
				if mode == "agent-config-null" {
					db.Model(&types.CustomAgent{}).Where("id=?", "agent").Update("config", "null")
				}
				if mode == "agent-other-malformed-any" {
					agentID = ""
					db.Create(&types.CustomAgent{ID: "bad", TenantID: 8, Name: "Bad", Config: types.CustomAgentConfig{KBSelectionMode: "none"}})
					db.Create(&types.AgentShare{ID: "share-bad", AgentID: "bad", OrganizationID: "org", SourceTenantID: 8, Permission: types.OrgRoleViewer})
					db.Model(&types.CustomAgent{}).Where("id=?", "bad").Update("config", "{")
				}
				if mode == "agent-explicit-missing-no-fallback" {
					agentID = "missing"
				}
				switch mode {
				case "agent-invalid-source":
					sourceSelector = "nope"
				case "agent-negative-source":
					sourceSelector = "-8"
				case "agent-overflow-source":
					sourceSelector = "18446744073709551616"
				case "agent-own-source":
					sourceSelector = "7"
				case "agent-explicit-correct-source":
					sourceSelector = "8"
				case "agent-explicit-wrong-source":
					sourceSelector = "9"
				}
			}
			if mode == "own-invalid-source-ignored" || mode == "org-invalid-source-ignored" {
				agentID = "missing"
				sourceSelector = "invalid"
			}
			agents := service.NewAgentShareService(repository.NewAgentShareRepository(db), repository.NewTenantDisabledSharedAgentRepository(db), repository.NewOrganizationRepository(db), repository.NewCustomAgentRepository(db), nil, nil, nil)
			kb := &types.KnowledgeBase{ID: "kb", Name: "KB", TenantID: owner, CreatorID: creator, StorageConfig: types.StorageConfig{SecretID: "owner-key-id", SecretKey: "owner-secret"}, VLMConfig: types.VLMConfig{APIKey: "owner-vlm-key"}}
			binding := "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
			kb.VectorStoreID = &binding
			if mode == "own-unbound" || mode == "shared-unbound" {
				kb.VectorStoreID = nil
			}
			if mode == "own-faq" || mode == "shared-faq" || mode == "faq-count-failure" {
				kb.Type = "faq"
			} else {
				kb.Type = "document"
			}
			if mode == "missing-viewer" || mode == "missing-admin" {
				kb = nil
			}
			if kb != nil {
				db.Create(kb)
			}
			db.Create(&types.VectorStore{ID: binding, Name: "Owner private storage", EngineType: "qdrant", TenantID: owner})
			for _, entry := range []struct{ id, status string }{{"done", "completed"}, {"pending", "pending"}, {"processing", "processing"}, {"deleting", "deleting"}, {"soft-deleted", "pending"}} {
				db.Create(&types.Knowledge{ID: entry.id, TenantID: owner, KnowledgeBaseID: "kb", Type: "file", ParseStatus: entry.status})
				if entry.id == "soft-deleted" {
					db.Where("id=?", entry.id).Delete(&types.Knowledge{})
				}
			}
			wrongTenant := uint64(7)
			if owner == 7 {
				wrongTenant = 8
			}
			db.Create(&types.Knowledge{ID: "foreign-doc", TenantID: wrongTenant, KnowledgeBaseID: "kb", Type: "file", ParseStatus: "pending"})
			db.Create(&types.Chunk{ID: "chunk", TenantID: owner, KnowledgeBaseID: "kb", KnowledgeID: "done", Content: "Owner chunk"})
			db.Create(&types.Chunk{ID: "foreign-chunk", TenantID: wrongTenant, KnowledgeBaseID: "kb", KnowledgeID: "foreign-doc", Content: "Foreign chunk"})

			fixed := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
			for i, entry := range []struct{ id, name, title, kind, file, channel, folder string }{{"done", "alpha.pdf", "Alpha 100%", "file", "pdf", "web", "docs"}, {"pending", "BETA.md", "Beta_under", "file", "md", "api", "docs/spec"}, {"processing", "", "Manual", "manual", "md", "web", ""}, {"deleting", "gone.pdf", "Deleting", "file", "pdf", "web", "docs"}, {"soft-deleted", "deleted.txt", "Deleted", "file", "txt", "web", ""}, {"foreign-doc", "foreign.txt", "Foreign", "file", "txt", "web", ""}} {
				db.Model(&types.Knowledge{}).Unscoped().Where("id=?", entry.id).Updates(map[string]any{"title": entry.title, "file_name": entry.name, "type": entry.kind, "file_type": entry.file, "channel": entry.channel, "folder_path": entry.folder, "source": "source/" + entry.id, "description": "Description " + entry.id, "created_at": fixed.Add(time.Duration(i) * 24 * time.Hour), "updated_at": fixed.Add(time.Duration(5-i) * 24 * time.Hour), "custom_metadata": types.JSON(`{"public":"value"}`)})
			}
			db.Model(&types.Chunk{}).Where("id=?", "chunk").Updates(map[string]any{"chunk_index": 0, "created_at": fixed, "updated_at": fixed, "source_content": "PRIVATE source", "last_editor_id": "editor", "metadata": types.JSON(`{"x":1}`), "context_header": "PRIVATE header"})
			for _, entry := range []struct {
				id, kind      string
				index, status int
			}{{"chunk-next", "text", 1, 2}, {"chunk-disabled", "text", 2, 0}, {"chunk-image", "image_caption", 3, 0}, {"chunk-pending", "text", 4, 1}, {"chunk-deleted", "text", 5, 0}} {
				db.Create(&types.Chunk{ID: entry.id, TenantID: owner, KnowledgeID: "done", KnowledgeBaseID: "kb", Content: entry.id, ChunkIndex: entry.index, ChunkType: entry.kind, Status: entry.status, IsEnabled: entry.id != "chunk-disabled", CreatedAt: fixed, UpdatedAt: fixed})
				if entry.id == "chunk-disabled" {
					db.Model(&types.Chunk{}).Where("id=?", entry.id).Update("is_enabled", false)
				}
				if entry.id == "chunk-deleted" {
					db.Where("id=?", entry.id).Delete(&types.Chunk{})
				}
			}
			db.Model(&types.Chunk{}).Where("id=?", "foreign-chunk").Updates(map[string]any{"knowledge_id": "done", "created_at": fixed, "updated_at": fixed})
			db.Create(&types.KnowledgeTag{ID: "tag-a", Name: "A", TenantID: owner, KnowledgeBaseID: "kb", CreatedAt: fixed, UpdatedAt: fixed})
			db.Create(&types.KnowledgeTag{ID: "tag-b", Name: "B", TenantID: owner, KnowledgeBaseID: "kb", CreatedAt: fixed, UpdatedAt: fixed})
			db.Create(&types.KnowledgeTagRelation{KnowledgeID: "done", TagID: "tag-a", CreatedAt: fixed})
			db.Create(&types.KnowledgeTagRelation{KnowledgeID: "pending", TagID: "tag-b", CreatedAt: fixed})
			if mode == "tag-foreign" {
				db.Model(&types.KnowledgeTag{}).Where("id=?", "tag-a").Update("tenant_id", wrongTenant)
			}
			if mode == "tag-table-failure" {
				db.Migrator().DropTable(&types.KnowledgeTag{})
			}
			if mode == "knowledge-owner-mismatch" {
				db.Model(&types.Knowledge{}).Where("id=?", "done").Update("tenant_id", wrongTenant)
			}
			if mode == "chunk-owner-mismatch" {
				db.Model(&types.Chunk{}).Where("id=?", "chunk").Update("tenant_id", wrongTenant)
			}
			if mode == "chunk-empty-kb" {
				db.Model(&types.Chunk{}).Where("id=?", "chunk").Update("knowledge_base_id", "")
			}
			if mode == "chunk-orphan-doc" {
				db.Model(&types.Chunk{}).Where("id=?", "chunk").Update("knowledge_id", "missing")
			}
			if mode == "knowledge-missing" {
				db.Where("id=?", "done").Delete(&types.Knowledge{})
			}
			if mode == "chunk-missing" {
				db.Where("id=?", "chunk").Delete(&types.Chunk{})
			}
			repo := repository.NewKnowledgeBaseRepository(db)
			counts := service.NewKnowledgeBaseService(repo, repository.NewKnowledgeRepository(db), repository.NewChunkRepository(db), nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil)
			ks := lookup{KnowledgeBaseService: counts, kb: kb, failure: mode == "lookup-failure-viewer" || mode == "lookup-failure-admin"}
			audit := service.NewAuditLogService(repository.NewAuditLogRepository(db))
			shares := service.NewKBShareService(repository.NewKBShareRepository(db), repository.NewOrganizationRepository(db), nil, nil, nil, audit)
			cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled, EnableCrossTenantAccess: true}}
			vectors := service.NewVectorStoreService(repository.NewVectorStoreRepository(db), repo, nil, nil, db)
			_ = vectors
			kgRepo := repository.NewKnowledgeRepository(db)
			chunkRepo := repository.NewChunkRepository(db)
			chunkSvc := service.NewChunkService(chunkRepo, kgRepo, repo, nil, nil, nil, nil, nil)
			kgSvc, e := service.NewKnowledgeService(cfg, kgRepo, nil, ks, nil, nil, chunkSvc, chunkRepo, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, shares, nil, nil, nil, nil, nil, nil)
			if e != nil {
				panic(e)
			}
			kh := handler.NewKnowledgeHandler(cfg, kgSvc, ks, shares, agents, chunkSvc, nil, nil, nil, nil, nil)
			ch := handler.NewChunkHandler(chunkSvc, kgSvc)
			var resolver middleware.KBIDResolver
			var handle gin.HandlerFunc
			method = "GET"
			switch endpoint {
			case "knowledge-list":
				template = "/api/v1/knowledge-bases/:id/knowledge"
				raw = "/api/v1/knowledge-bases/kb/knowledge"
				resolver = middleware.KBIDFromParam("id")
				handle = kh.ListKnowledge
			case "knowledge-detail":
				template = "/api/v1/knowledge/:id"
				raw = "/api/v1/knowledge/done"
				resolver = middleware.KBIDFromKnowledgeIDParam("id", kgSvc)
				handle = kh.GetKnowledge
			case "chunk-list":
				template = "/api/v1/chunks/:knowledge_id"
				raw = "/api/v1/chunks/done"
				resolver = middleware.KBIDFromKnowledgeIDParam("knowledge_id", kgSvc)
				handle = ch.ListKnowledgeChunks
			case "chunk-detail":
				template = "/api/v1/chunks/by-id/:id"
				raw = "/api/v1/chunks/by-id/chunk"
				resolver = middleware.KBIDFromChunkIDParam("id", chunkSvc)
				handle = ch.GetChunkByIDOnly
			}

			payloads := map[string]string{"profile-typed": "{\"gist\":\"Summary\",\"topics\":[\"A\",\"B\"],\"doc_type\":\"\",\"typical_question\":\"Question\"}", "profile-null": "null", "profile-empty": "", "profile-wrong-type": "{\"gist\":1}", "profile-unknown": "{\"unknown\":\"PRIVATE\",\"topics\":[],\"doc_type\":\"report\"}", "profile-duplicate-null": "{\"gist\":\"Summary\",\"gist\":null}", "locators-typed": "[{\"type\":\"pdf\",\"page\":2,\"bbox\":[0,0.2,0.8,1],\"quote\":\"Evidence\",\"mapping\":\"exact\",\"partial\":false}]", "locators-null": "null", "locators-empty": "", "locators-unknown": "[{\"type\":\"text\",\"start\":0,\"end\":12,\"unknown\":\"PRIVATE\",\"quote\":\"text\"}]", "locators-wrong-type": "[{\"type\":\"pdf\",\"page\":\"2\"}]", "locators-duplicate-null": "[{\"type\":\"pdf\",\"page\":2,\"page\":null,\"quote\":null}]"}
			if payload, ok := payloads[mode]; ok {
				if strings.HasPrefix(mode, "profile-") {
					db.Model(&types.Knowledge{}).Where("id=?", "done").UpdateColumn("profile", payload)
				} else {
					db.Model(&types.Chunk{}).Where("id=?", "chunk").UpdateColumn("source_locators", payload)
				}
			}
			if mode == "lookup-knowledge-failure" {
				db.Migrator().DropTable(&types.Knowledge{})
			}
			if mode == "lookup-chunk-failure" {
				db.Migrator().DropTable(&types.Chunk{})
			}
			if mode == "tag-relation-failure" {
				db.Migrator().DropTable(&types.KnowledgeTagRelation{})
			}
			if mode == "knowledge-missing-kb-link" {
				db.Model(&types.Knowledge{}).Where("id=?", "done").UpdateColumn("knowledge_base_id", "missing")
			}
			if mode == "chunk-kb-link-other" {
				db.Model(&types.Chunk{}).Where("id=?", "chunk").UpdateColumn("knowledge_base_id", "missing")
			}
			db.Model(&types.Chunk{}).Where("1=1").UpdateColumn("updated_at", fixed)
			db.Model(&types.KnowledgeTag{}).Where("1=1").UpdateColumn("updated_at", fixed)
			if mode == "knowledge-owner-mismatch" {
				db.Model(&types.Knowledge{}).Where("id=?", "done").UpdateColumn("updated_at", fixed.Add(5*24*time.Hour))
			}
			rawPayloads := map[string][]string{"raw-metadata-empty": {"metadata", ""}, "raw-metadata-null": {"metadata", "null"}, "raw-metadata-space": {"metadata", " "}, "raw-metadata-object": {"metadata", "{\"a\":1}"}, "raw-metadata-duplicate": {"metadata", "{\"a\":1,\"a\":2}"}, "raw-metadata-array": {"metadata", "[1,true,null,\"x\"]"}, "raw-metadata-string": {"metadata", "\"text\""}, "raw-metadata-boolean": {"metadata", "true"}, "raw-metadata-zero": {"metadata", "-0"}, "raw-metadata-huge-float": {"metadata", "1e400"}, "raw-metadata-exact-integer": {"metadata", "9007199254740993"}, "raw-metadata-fraction": {"metadata", "0.12345678901234567890123456789"}, "raw-metadata-trailing-value": {"metadata", "{} {}"}, "raw-metadata-trailing-garbage": {"metadata", "{} trailing"}, "raw-metadata-bad-array": {"metadata", "[1,]"}, "raw-metadata-bad-number": {"metadata", "01"}, "raw-metadata-empty-object": {"metadata", "{}"}, "raw-custom_metadata-empty": {"custom_metadata", ""}, "raw-custom_metadata-null": {"custom_metadata", "null"}, "raw-custom_metadata-space": {"custom_metadata", " "}, "raw-custom_metadata-object": {"custom_metadata", "{\"a\":1}"}, "raw-custom_metadata-duplicate": {"custom_metadata", "{\"a\":1,\"a\":2}"}, "raw-custom_metadata-array": {"custom_metadata", "[1,true,null,\"x\"]"}, "raw-custom_metadata-string": {"custom_metadata", "\"text\""}, "raw-custom_metadata-boolean": {"custom_metadata", "true"}, "raw-custom_metadata-zero": {"custom_metadata", "-0"}, "raw-custom_metadata-huge-float": {"custom_metadata", "1e400"}, "raw-custom_metadata-exact-integer": {"custom_metadata", "9007199254740993"}, "raw-custom_metadata-fraction": {"custom_metadata", "0.12345678901234567890123456789"}, "raw-custom_metadata-trailing-value": {"custom_metadata", "{} {}"}, "raw-custom_metadata-trailing-garbage": {"custom_metadata", "{} trailing"}, "raw-custom_metadata-bad-array": {"custom_metadata", "[1,]"}, "raw-custom_metadata-bad-number": {"custom_metadata", "01"}, "raw-custom_metadata-empty-object": {"custom_metadata", "{}"}, "raw-last_faq_import_result-empty": {"last_faq_import_result", ""}, "raw-last_faq_import_result-null": {"last_faq_import_result", "null"}, "raw-last_faq_import_result-space": {"last_faq_import_result", " "}, "raw-last_faq_import_result-object": {"last_faq_import_result", "{\"a\":1}"}, "raw-last_faq_import_result-duplicate": {"last_faq_import_result", "{\"a\":1,\"a\":2}"}, "raw-last_faq_import_result-array": {"last_faq_import_result", "[1,true,null,\"x\"]"}, "raw-last_faq_import_result-string": {"last_faq_import_result", "\"text\""}, "raw-last_faq_import_result-boolean": {"last_faq_import_result", "true"}, "raw-last_faq_import_result-zero": {"last_faq_import_result", "-0"}, "raw-last_faq_import_result-huge-float": {"last_faq_import_result", "1e400"}, "raw-last_faq_import_result-exact-integer": {"last_faq_import_result", "9007199254740993"}, "raw-last_faq_import_result-fraction": {"last_faq_import_result", "0.12345678901234567890123456789"}, "raw-last_faq_import_result-trailing-value": {"last_faq_import_result", "{} {}"}, "raw-last_faq_import_result-trailing-garbage": {"last_faq_import_result", "{} trailing"}, "raw-last_faq_import_result-bad-array": {"last_faq_import_result", "[1,]"}, "raw-last_faq_import_result-bad-number": {"last_faq_import_result", "01"}, "raw-last_faq_import_result-empty-object": {"last_faq_import_result", "{}"}, "raw-relation_chunks-empty": {"relation_chunks", ""}, "raw-relation_chunks-null": {"relation_chunks", "null"}, "raw-relation_chunks-space": {"relation_chunks", " "}, "raw-relation_chunks-object": {"relation_chunks", "{\"a\":1}"}, "raw-relation_chunks-duplicate": {"relation_chunks", "{\"a\":1,\"a\":2}"}, "raw-relation_chunks-array": {"relation_chunks", "[1,true,null,\"x\"]"}, "raw-relation_chunks-string": {"relation_chunks", "\"text\""}, "raw-relation_chunks-boolean": {"relation_chunks", "true"}, "raw-relation_chunks-zero": {"relation_chunks", "-0"}, "raw-relation_chunks-huge-float": {"relation_chunks", "1e400"}, "raw-relation_chunks-exact-integer": {"relation_chunks", "9007199254740993"}, "raw-relation_chunks-fraction": {"relation_chunks", "0.12345678901234567890123456789"}, "raw-relation_chunks-trailing-value": {"relation_chunks", "{} {}"}, "raw-relation_chunks-trailing-garbage": {"relation_chunks", "{} trailing"}, "raw-relation_chunks-bad-array": {"relation_chunks", "[1,]"}, "raw-relation_chunks-bad-number": {"relation_chunks", "01"}, "raw-relation_chunks-empty-object": {"relation_chunks", "{}"}, "raw-indirect_relation_chunks-empty": {"indirect_relation_chunks", ""}, "raw-indirect_relation_chunks-null": {"indirect_relation_chunks", "null"}, "raw-indirect_relation_chunks-space": {"indirect_relation_chunks", " "}, "raw-indirect_relation_chunks-object": {"indirect_relation_chunks", "{\"a\":1}"}, "raw-indirect_relation_chunks-duplicate": {"indirect_relation_chunks", "{\"a\":1,\"a\":2}"}, "raw-indirect_relation_chunks-array": {"indirect_relation_chunks", "[1,true,null,\"x\"]"}, "raw-indirect_relation_chunks-string": {"indirect_relation_chunks", "\"text\""}, "raw-indirect_relation_chunks-boolean": {"indirect_relation_chunks", "true"}, "raw-indirect_relation_chunks-zero": {"indirect_relation_chunks", "-0"}, "raw-indirect_relation_chunks-huge-float": {"indirect_relation_chunks", "1e400"}, "raw-indirect_relation_chunks-exact-integer": {"indirect_relation_chunks", "9007199254740993"}, "raw-indirect_relation_chunks-fraction": {"indirect_relation_chunks", "0.12345678901234567890123456789"}, "raw-indirect_relation_chunks-trailing-value": {"indirect_relation_chunks", "{} {}"}, "raw-indirect_relation_chunks-trailing-garbage": {"indirect_relation_chunks", "{} trailing"}, "raw-indirect_relation_chunks-bad-array": {"indirect_relation_chunks", "[1,]"}, "raw-indirect_relation_chunks-bad-number": {"indirect_relation_chunks", "01"}, "raw-indirect_relation_chunks-empty-object": {"indirect_relation_chunks", "{}"}}
			if p, ok := rawPayloads[mode]; ok {
				table, id := "knowledges", "done"
				if p[0] == "relation_chunks" || p[0] == "indirect_relation_chunks" {
					table, id = "chunks", "chunk"
				}
				db.Table(table).Where("id=?", id).UpdateColumn(p[0], p[1])
				if p[0] == "metadata" {
					db.Table("chunks").Where("id=?", "chunk").UpdateColumn("metadata", p[1])
				}
			}
			gate := middleware.NewAPIKeyRouteAuthorizer()
			cap := types.APIKeyCapabilityRetrieve
			if method == "PUT" {
				cap = types.APIKeyCapabilityManageKnowledgeBases
			}
			gate.Register(method, template, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(cap))
			r := gin.New()
			r.Use(middleware.ErrorHandler(), middleware.AuditServiceProvider(audit))
			r.Use(func(c *gin.Context) {
				ctx := context.WithValue(c.Request.Context(), types.UserIDContextKey, "caller")
				ctx = context.WithValue(ctx, types.TenantIDContextKey, uint64(7))
				ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
				ctx = context.WithValue(ctx, types.SystemAdminContextKey, system)
				ctx = context.WithValue(ctx, types.UserContextKey, &types.User{ID: "caller", TenantID: 7, CanAccessAllTenants: cross, IsSystemAdmin: system})
				if machine {
					ctx = types.WithTenantAPIKeyScope(ctx, scope)
				}
				c.Set(types.TenantIDContextKey.String(), uint64(7))
				c.Set(types.UserIDContextKey.String(), "caller")
				c.Request = c.Request.WithContext(ctx)
				c.Next()
			})
			r.Handle(method, template, gate.Middleware(), middleware.RequireRole(types.TenantRoleViewer, cfg), middleware.RequireKBAccess(resolver, types.OrgRoleViewer, ks, shares, agents, cfg), handle)
			values := url.Values{}
			if agentID != "" {
				values.Set("agent_id", agentID)
			}
			if sourceSelector != "" {
				values.Set("agent_source_tenant_id", sourceSelector)
			}
			if len(values) > 0 {
				raw += "?" + values.Encode()
			}
			queries := map[string]string{"edge-time-0": "start_time=", "edge-time-1": "start_time=%20", "edge-time-2": "start_time=%C2%A0%E2%80%80", "edge-time-3": "start_time=2026-10-02", "edge-time-4": "start_time=2026-10-02T00%3A00%3A00Z", "edge-time-5": "start_time=2026-10-02T0%3A00%3A00Z", "edge-time-6": "start_time=2026-10-02T09%3A08%3A07%2C123Z", "edge-time-7": "start_time=2026-10-02T09%3A08%3A07.123456789987Z", "edge-time-8": "start_time=2026-10-02T09%3A08%3A07.Z", "edge-time-9": "start_time=2026-10-02T09%3A08%3A07%2CZ", "edge-time-10": "start_time=2026-10-02T09%3A08%3A07%2B24%3A00", "edge-time-11": "start_time=2026-10-02T09%3A08%3A07%2B24%3A60", "edge-time-12": "start_time=2026-10-02T09%3A08%3A07-24%3A60", "edge-time-13": "start_time=2026-10-02T09%3A08%3A07%2B00%3A60", "edge-time-14": "start_time=2026-10-02T09%3A08%3A07%2B25%3A00", "edge-time-15": "start_time=2026-10-02T09%3A08%3A07%2B02%3A61", "edge-time-16": "start_time=2026-10-02T09%3A08%3A07%2B18%3A59", "edge-time-17": "start_time=2026-10-02T09%3A08%3A07z", "edge-time-18": "start_time=2026-10-02t09%3A08%3A07Z", "edge-time-19": "start_time=2026-10-02T09%3A08Z", "edge-time-20": "start_time=2026-10-02T24%3A00%3A00Z", "edge-time-21": "start_time=2026-10-02T09%3A8%3A07Z", "edge-time-22": "start_time=2026-10-02T09%3A08%3A7Z", "edge-time-23": "start_time=2026-10-02%209%3A08%3A07", "edge-time-24": "start_time=2026-10-02%20%20%2009%3A08%3A07", "edge-time-25": "start_time=2026-10-02%0909%3A08%3A07", "edge-time-26": "start_time=2026-10-02%2009%3A08%3A07.123456789987", "edge-time-27": "start_time=2026-10-02%2009%3A08%3A07%2C123", "edge-time-28": "start_time=2026-10-02%2009%3A08%3A07Z", "edge-time-29": "start_time=2026-10-02%2009%3A08%3A07%2B08%3A00", "edge-time-30": "start_time=2026-10-02%2024%3A00%3A00", "edge-time-31": "start_time=2026-10-02%2009%3A8%3A07", "edge-time-32": "start_time=2026-10-02%2009%3A08%3A7", "edge-time-33": "start_time=2026-10-02%2009%3A08%3A60", "edge-time-34": "start_time=0000-01-01", "edge-time-35": "start_time=0001-01-01", "edge-time-36": "start_time=9999-12-31", "edge-time-37": "start_time=10000-01-01", "edge-time-38": "start_time=-001-01-01", "edge-time-39": "start_time=2026-1-02", "edge-time-40": "start_time=2026-10-2", "edge-time-41": "start_time=2026-00-01", "edge-time-42": "start_time=2026-13-01", "edge-time-43": "start_time=2026-02-29", "edge-time-44": "start_time=2024-02-29", "edge-time-45": "start_time=2026-04-31", "edge-time-46": "start_time=2026-10-00", "edge-time-47": "start_time=2026-10-32", "edge-time-48": "start_time=2026-10-02extra", "edge-time-49": "start_time=2026-10-02%0Aextra", "edge-time-50": "start_time=2026-10-02%22", "edge-time-51": "start_time=2026-10-02%E2%80%A8extra", "edge-time-52": "start_time=2026-10", "edge-time-53": "start_time=2026", "edge-time-54": "start_time=x", "edge-time-55": "start_time=%C2%A0%202026-10-02%20%E3%80%80", "edge-time-56": "start_time=2026-10-02%E2%80%8B", "edge-time-57": "start_time=2026-03-08%2002%3A30%3A00", "edge-time-58": "start_time=2026-11-01%2001%3A30%3A00", "edge-time-59": "start_time=1919-01-01%2000%3A00%3A00", "edge-time-60": "start_time=1880-01-01%2000%3A00%3A00", "edge-time-61": "start_time=2026-03-29%2002%3A30%3A00", "edge-time-62": "start_time=2026-10-25%2002%3A30%3A00", "edge-time-63": "start_time=2026-10-02T09%3A08%3A07.0Z", "edge-time-64": "start_time=2026-10-02T09%3A08%3A07%2C0Z", "edge-time-65": "start_time=2026-10-02T09%3A08%3A07.000Z", "edge-time-66": "start_time=2026-10-02T09%3A08%3A07%2C000Z", "edge-time-67": "start_time=2026-10-02T09%3A08%3A07.1Z", "edge-time-68": "start_time=2026-10-02T09%3A08%3A07%2C1Z", "edge-time-69": "start_time=2026-10-02T09%3A08%3A07.123456789Z", "edge-time-70": "start_time=2026-10-02T09%3A08%3A07%2C123456789Z", "edge-time-71": "start_time=2026-10-02T09%3A08%3A07.1234567890Z", "edge-time-72": "start_time=2026-10-02T09%3A08%3A07%2C1234567890Z", "edge-time-73": "start_time=2026-10-02T09%3A08%3A07.9999999999999999999999999999Z", "edge-time-74": "start_time=2026-10-02T09%3A08%3A07%2C9999999999999999999999999999Z"}
			if strings.HasPrefix(mode, "query-") {
				separator := "?"
				if strings.Contains(raw, "?") {
					separator = "&"
				}
				raw += separator + queries[strings.TrimPrefix(mode, "query-")]
			}
			w := httptest.NewRecorder()
			r.ServeHTTP(w, httptest.NewRequest(method, raw, nil))
			var body any
			dec := json.NewDecoder(strings.NewReader(w.Body.String()))
			dec.UseNumber()
			if e := dec.Decode(&body); e != nil {
				panic(string(w.Body.Bytes()))
			}
			var logs []types.AuditLog
			db.Order("id").Find(&logs)
			rows := []map[string]any{}
			for _, entry := range logs {
				var details any
				json.Unmarshal(entry.Details, &details)
				rows = append(rows, map[string]any{"tenant_id": entry.TenantID, "actor_user_id": entry.ActorUserID, "actor_role": entry.ActorRole, "action": entry.Action, "request_path": entry.RequestPath, "request_method": entry.RequestMethod, "outcome": entry.Outcome, "details": details})
			}
			out[endpoint+"/"+mode] = map[string]any{"status": w.Code, "body": body, "audits": rows}
			sqldb, _ := db.DB()
			sqldb.Close()
		}
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	enc.Encode(out)
}
