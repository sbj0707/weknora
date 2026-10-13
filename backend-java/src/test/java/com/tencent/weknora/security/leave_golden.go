//go:build ignore

// Run from repository root to regenerate leave-golden.json using the production Go handler, service and repositories.
package main

import (
	"context"
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"net/http/httptest"
	"os"
)

func main() {
	gin.SetMode(gin.TestMode)
	results := map[string]any{}
	for _, mode := range []string{"viewer", "last_owner", "other_owner", "missing", "no_caller"} {
		user := "u"
		if mode == "viewer" {
			user = "v"
		}
		db, e := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		if e != nil {
			panic(e)
		}
		if e = db.AutoMigrate(&types.TenantMember{}, &types.AuditLog{}); e != nil {
			panic(e)
		}
		if mode != "missing" && mode != "no_caller" {
			role := types.TenantRoleViewer
			if mode != "viewer" {
				role = types.TenantRoleOwner
			}
			if e = db.Create(&types.TenantMember{UserID: user, TenantID: 1, Role: role, Status: types.TenantMemberStatusActive}).Error; e != nil {
				panic(e)
			}
		}
		if mode == "other_owner" {
			_ = db.Create(&types.TenantMember{UserID: "other", TenantID: 1, Role: types.TenantRoleOwner, Status: types.TenantMemberStatusActive}).Error
		}
		members := service.NewTenantMemberService(repository.NewTenantMemberRepository(db), service.NewAuditLogService(repository.NewAuditLogRepository(db)), nil, nil)
		h := handler.NewTenantMemberHandler(members, nil)
		r := gin.New()
		r.Use(middleware.ErrorHandler())
		r.POST("/api/v1/tenants/:id/leave", func(c *gin.Context) {
			actorRole := types.TenantRoleViewer
			if mode == "last_owner" || mode == "other_owner" {
				actorRole = types.TenantRoleOwner
			}
			ctx := context.WithValue(c.Request.Context(), types.TenantRoleContextKey, actorRole)
			if mode != "no_caller" {
				ctx = context.WithValue(ctx, types.UserIDContextKey, user)
			}
			c.Request = c.Request.WithContext(ctx)
			h.LeaveTenant(c)
		})
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/tenants/1/leave", nil))
		var body any
		_ = json.Unmarshal(w.Body.Bytes(), &body)
		var audit types.AuditLog
		_ = db.First(&audit).Error
		results[mode] = map[string]any{"status": w.Code, "body": body}
		if audit.ID != 0 {
			results[mode].(map[string]any)["audit"] = map[string]any{"actor_user_id": audit.ActorUserID, "actor_role": audit.ActorRole, "action": audit.Action, "target_type": audit.TargetType, "target_user_id": audit.TargetUserID, "outcome": audit.Outcome}
		}
		sql, _ := db.DB()
		_ = sql.Close()
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if e := enc.Encode(results); e != nil {
		panic(e)
	}
}
