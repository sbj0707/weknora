//go:build ignore

// Production Go role middleware and LogDenied repository oracle.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
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
	"os"
	"time"
)

type failedCount struct{ interfaces.AuditLogRepository }

func (f failedCount) CountSinceForDedup(context.Context, uint64, string, types.AuditAction, string, time.Time) (int64, error) {
	return 0, errors.New("oracle count failure")
}
func main() {
	log.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	results := map[string]any{}
	for _, mode := range []string{"denied", "template-dedup", "different-method-dedup", "different-actor", "different-tenant", "expired-window", "failed-count", "rbac-disabled", "cross-superuser", "api-key", "system-admin-not-owner", "admin-system-bypass"} {
		db, e := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		if e != nil {
			panic(e)
		}
		if e = db.AutoMigrate(&types.AuditLog{}); e != nil {
			panic(e)
		}
		var repo interfaces.AuditLogRepository = repository.NewAuditLogRepository(db)
		if mode == "failed-count" {
			repo = failedCount{repo}
		}
		audit := service.NewAuditLogService(repo)
		enabled := mode != "rbac-disabled"
		cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled, EnableCrossTenantAccess: true}}
		r := gin.New()
		r.Use(middleware.AuditServiceProvider(audit))
		r.Use(func(c *gin.Context) {
			role := types.TenantRoleViewer
			actor := "caller"
			tenant := uint64(7)
			if mode == "different-actor" && c.Param("inv_id") == "2" {
				actor = "other"
			}
			if mode == "different-tenant" && c.Param("inv_id") == "2" {
				tenant = 8
			}
			ctx := context.WithValue(c.Request.Context(), types.UserIDContextKey, actor)
			ctx = context.WithValue(ctx, types.TenantIDContextKey, tenant)
			ctx = context.WithValue(ctx, types.TenantRoleContextKey, role)
			ctx = context.WithValue(ctx, types.SystemAdminContextKey, mode == "system-admin-not-owner" || mode == "admin-system-bypass")
			ctx = context.WithValue(ctx, types.UserContextKey, &types.User{ID: actor, TenantID: tenant, CanAccessAllTenants: mode == "cross-superuser", IsSystemAdmin: mode == "system-admin-not-owner" || mode == "admin-system-bypass"})
			if mode == "api-key" {
				ctx = types.WithTenantAPIKeyScope(ctx, types.TenantAPIKeyScope{})
			}
			c.Request = c.Request.WithContext(ctx)
			c.Next()
		})
		guard := middleware.RequireRole(types.TenantRoleOwner, cfg)
		if mode == "admin-system-bypass" {
			guard = middleware.RequireRoleOrSystemAdmin(types.TenantRoleAdmin, cfg)
		}
		r.POST("/api/v1/tenants/:id/invitations/:inv_id", guard, func(c *gin.Context) { c.JSON(200, gin.H{"ok": true}) })
		r.DELETE("/api/v1/tenants/:id/invitations/:inv_id", guard, func(c *gin.Context) { c.JSON(200, gin.H{"ok": true}) })
		responses := []map[string]any{}
		n := 1
		if mode == "template-dedup" || mode == "different-method-dedup" || mode == "different-actor" || mode == "different-tenant" || mode == "expired-window" || mode == "failed-count" {
			n = 2
		}
		for i := 1; i <= n; i++ {
			if i == 2 && mode == "expired-window" {
				db.Model(&types.AuditLog{}).Where("1=1").Update("created_at", time.Now().Add(-61*time.Second))
			}
			method := "POST"
			if i == 2 && mode == "different-method-dedup" {
				method = "DELETE"
			}
			path := "/api/v1/tenants/7/invitations/1"
			if i == 2 {
				path = "/api/v1/tenants/7/invitations/2"
			}
			w := httptest.NewRecorder()
			r.ServeHTTP(w, httptest.NewRequest(method, path, nil))
			var body any
			json.Unmarshal(w.Body.Bytes(), &body)
			responses = append(responses, map[string]any{"status": w.Code, "body": body})
		}
		var logs []types.AuditLog
		db.Order("id").Find(&logs)
		rows := []map[string]any{}
		for _, entry := range logs {
			var details any
			json.Unmarshal(entry.Details, &details)
			rows = append(rows, map[string]any{"tenant_id": entry.TenantID, "actor_user_id": entry.ActorUserID, "actor_role": entry.ActorRole, "action": entry.Action, "request_path": entry.RequestPath, "request_method": entry.RequestMethod, "outcome": entry.Outcome, "details": details})
		}
		results[mode] = map[string]any{"responses": responses, "audits": rows}
		sql, _ := db.DB()
		sql.Close()
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	enc.Encode(results)
}
