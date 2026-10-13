//go:build ignore

// go run backend-java/src/test/java/com/tencent/weknora/security/orphan_golden.go > backend-java/src/test/java/com/tencent/weknora/security/orphan-golden.json
package main

import (
	"context"
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/types"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"os"
)

func main() {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	if err != nil {
		panic(err)
	}
	if err = db.AutoMigrate(&types.TenantMember{}, &types.AuditLog{}); err != nil {
		panic(err)
	}
	members := service.NewTenantMemberService(repository.NewTenantMemberRepository(db), service.NewAuditLogService(repository.NewAuditLogRepository(db)), nil, nil)
	ctx := context.Background()
	row, err := members.AddMember(ctx, "registrant", 1, types.TenantRoleOwner, nil)
	if err != nil {
		panic(err)
	}
	var audit types.AuditLog
	_ = db.First(&audit).Error
	_, duplicate := members.AddMember(ctx, "registrant", 1, types.TenantRoleOwner, nil)
	_ = db.Create(&types.TenantMember{UserID: "suspended", TenantID: 2, Role: types.TenantRoleViewer, Status: types.TenantMemberStatusSuspended}).Error
	has, err := members.HasAnyMembers(ctx, 2)
	if err != nil {
		panic(err)
	}
	_, blocked := members.AddMember(ctx, "suspended", 2, types.TenantRoleOwner, nil)
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(map[string]any{"recovered": map[string]any{"role": row.Role, "status": row.Status, "invited_by": row.InvitedBy}, "audit": map[string]any{"actor_user_id": audit.ActorUserID, "actor_role": audit.ActorRole, "action": audit.Action, "target_type": audit.TargetType, "target_user_id": audit.TargetUserID, "outcome": audit.Outcome}, "duplicate_error": duplicate.Error(), "suspended_blocks_add": blocked.Error(), "suspended_counts_as_active": has})
}
