//go:build ignore

// Executes production Go invitation registration HTTP handler, services and SQLite repositories.
package main

import (
	"context"
	"encoding/json"
	"fmt"
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
	"os"
	"strings"
	"time"
)

type tenants struct{ interfaces.TenantService }

func (t tenants) GetTenantByID(ctx context.Context, id uint64) (*types.Tenant, error) {
	return &types.Tenant{ID: id, Name: "Workspace", Status: "active"}, nil
}
func (t tenants) GetTenantsByIDs(ctx context.Context, ids []uint64) (map[uint64]*types.Tenant, error) {
	out := map[uint64]*types.Tenant{}
	for _, id := range ids {
		out[id] = &types.Tenant{ID: id, Name: "Workspace", Status: "active"}
	}
	return out, nil
}
func main() {
	log.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	os.Setenv("JWT_SECRET", "register-oracle-real-secret")
	outputs := map[string]any{}
	for _, mode := range []string{"success", "password-controls", "duplicate-email", "duplicate-username", "disabled", "invite-only", "invalid-token", "expired", "member-failure", "home-failure", "rollback-failure", "access-token-failure", "refresh-token-failure", "create-user-failure", "short-password", "invalid-policy", "trimmed-username-empty"} {
		db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		if err != nil {
			panic(err)
		}
		if err = db.AutoMigrate(&types.User{}, &types.AuthToken{}, &types.TenantMember{}, &types.TenantInvitation{}, &types.AuditLog{}); err != nil {
			panic(err)
		}
		db.Exec("CREATE UNIQUE INDEX unique_members ON tenant_members(user_id,tenant_id)")
		audit := service.NewAuditLogService(repository.NewAuditLogRepository(db))
		members := service.NewTenantMemberService(repository.NewTenantMemberRepository(db), audit, nil, nil)
		invitations := service.NewTenantInvitationService(repository.NewTenantInvitationRepository(db), members, audit)
		cfg := &config.Config{Auth: &config.AuthConfig{RegistrationMode: "self_serve"}}
		switch mode {
		case "disabled":
			cfg.Auth.RegistrationMode = "disabled"
		case "invite-only":
			cfg.Auth.RegistrationMode = "invite_only"
		}
		users := service.NewUserService(cfg, repository.NewUserRepository(db), repository.NewAuthTokenRepository(db), tenants{}, members, nil)
		db.Create(&types.TenantInvitation{TenantID: 7, Role: types.TenantRoleContributor, Status: types.TenantInvitationStatusPending, Token: "shared-link-token", ExpiresAt: time.Now().Add(time.Hour)})
		email, username, password, token := "NEW@example.com", " tester ", "abc12345", "shared-link-token"
		switch mode {
		case "duplicate-email":
			db.Create(&types.User{ID: "existing", Username: "existing", Email: "new@example.com", IsActive: true})
		case "duplicate-username":
			db.Create(&types.User{ID: "existing", Username: "tester", Email: "existing@example.com", IsActive: true})
		case "invalid-token":
			token = "invalid"
		case "expired":
			db.Exec("UPDATE tenant_invitations SET expires_at=?", time.Now().Add(-time.Hour))
		case "password-controls":
			password = "Abc123\n45"
		case "short-password":
			password = "12345"
		case "invalid-policy":
			password = "abcdefgh"
		case "trimmed-username-empty":
			username = "  "
		}
		triggers := []string{}
		switch mode {
		case "member-failure", "rollback-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_member BEFORE INSERT ON tenant_members BEGIN SELECT RAISE(ABORT,'oracle member failure'); END")
		case "home-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_home BEFORE UPDATE ON users WHEN NEW.tenant_id=7 BEGIN SELECT RAISE(ABORT,'oracle home failure'); END")
		case "access-token-failure", "refresh-token-failure":
			kind := "access_token"
			if mode == "refresh-token-failure" {
				kind = "refresh_token"
			}
			triggers = append(triggers, fmt.Sprintf("CREATE TRIGGER fail_token BEFORE INSERT ON auth_tokens WHEN NEW.token_type='%s' BEGIN SELECT RAISE(ABORT,'oracle token failure'); END", kind))
		case "create-user-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_create BEFORE INSERT ON users BEGIN SELECT RAISE(ABORT,'oracle create failure'); END")
		}
		if mode == "rollback-failure" {
			triggers = append(triggers, "CREATE TRIGGER fail_rollback BEFORE UPDATE ON users WHEN COALESCE(NEW.tenant_id,0)=0 AND OLD.tenant_id=7 BEGIN SELECT RAISE(ABORT,'oracle rollback failure'); END")
		}
		for _, sql := range triggers {
			if err = db.Exec(sql).Error; err != nil {
				panic(err)
			}
		}
		h := handler.NewAuthHandler(cfg, users, tenants{}, nil, invitations)
		r := gin.New()
		r.Use(middleware.ErrorHandler())
		r.POST("/register", h.RegisterByInvite)
		raw, _ := json.Marshal(map[string]string{"token": token, "email": email, "username": username, "password": password})
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest("POST", "/register", strings.NewReader(string(raw))))
		var body map[string]any
		json.Unmarshal(w.Body.Bytes(), &body)
		summary := map[string]any{"status": w.Code}
		if e, ok := body["error"].(map[string]any); ok {
			summary["code"] = e["code"]
			summary["message"] = e["message"]
		} else {
			summary["message"] = body["message"]
			profile := body["user"].(map[string]any)
			summary["profile"] = map[string]any{"username": profile["username"], "email": profile["email"], "tenant_id": profile["tenant_id"], "is_active": profile["is_active"], "can_access_all_tenants": profile["can_access_all_tenants"], "is_system_admin": profile["is_system_admin"], "preferences": profile["preferences"]}
			summary["memberships"] = body["memberships"]
		}
		var us []types.User
		db.Unscoped().Where("email=?", "new@example.com").Find(&us)
		ur := []map[string]any{}
		for _, u := range us {
			ur = append(ur, map[string]any{"username": u.Username, "email": u.Email, "tenant_id": u.TenantID, "active": u.IsActive, "deleted": u.DeletedAt.Valid})
		}
		summary["users"] = ur
		var ms []types.TenantMember
		db.Find(&ms)
		mr := []map[string]any{}
		for _, m := range ms {
			mr = append(mr, map[string]any{"tenant_id": m.TenantID, "role": m.Role, "status": m.Status})
		}
		summary["member_rows"] = mr
		var count int64
		db.Model(&types.AuthToken{}).Count(&count)
		summary["token_rows"] = count
		var inv types.TenantInvitation
		db.First(&inv)
		summary["accepted_count"] = inv.AcceptedCount
		var as []types.AuditLog
		db.Order("id").Find(&as)
		ar := []map[string]any{}
		for _, a := range as {
			ar = append(ar, map[string]any{"actor_user_id": a.ActorUserID, "actor_role": a.ActorRole, "action": a.Action, "outcome": a.Outcome})
		}
		summary["audits"] = ar
		outputs[mode] = map[string]any{"request": map[string]string{"token": token, "email": email, "username": username, "password": password}, "result": summary}
		sql, _ := db.DB()
		sql.Close()
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	enc.Encode(outputs)
}
