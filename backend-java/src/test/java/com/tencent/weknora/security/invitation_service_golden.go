//go:build ignore

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
	"time"
)

func main() {
	outputs := map[string]any{}
	for _, mode := range []string{"create", "duplicate", "active-member", "accept", "accept-twice", "wrong-invitee", "expired", "decline", "revoke", "revoke-twice", "existing-suspended", "share-multi", "share-existing", "share-reconcile", "share-revoked"} {
		db, e := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		if e != nil {
			panic(e)
		}
		if e = db.AutoMigrate(&types.TenantMember{}, &types.TenantInvitation{}, &types.AuditLog{}); e != nil {
			panic(e)
		}
		_ = db.Exec("CREATE UNIQUE INDEX unique_members ON tenant_members(user_id,tenant_id)").Error
		audit := service.NewAuditLogService(repository.NewAuditLogRepository(db))
		members := service.NewTenantMemberService(repository.NewTenantMemberRepository(db), audit, nil, nil)
		invs := service.NewTenantInvitationService(repository.NewTenantInvitationRepository(db), members, audit)
		owner := context.WithValue(context.WithValue(context.Background(), types.UserIDContextKey, "owner"), types.TenantRoleContextKey, types.TenantRoleOwner)
		invitee := context.WithValue(context.WithValue(context.Background(), types.UserIDContextKey, "invitee"), types.TenantRoleContextKey, types.TenantRoleViewer)
		actor := "owner"
		errs := []string{}
		record := func(e error) {
			if e != nil {
				errs = append(errs, e.Error())
			}
		}
		share := len(mode) > 5 && mode[:5] == "share"
		if mode == "active-member" {
			_, e = members.AddMember(owner, "invitee", 7, types.TenantRoleAdmin, &actor)
			record(e)
		}
		var inv *types.TenantInvitation
		var token string
		if share {
			inv, token, e = invs.CreateShareLink(owner, 7, types.TenantRoleContributor, &actor, "note")
		} else {
			inv, e = invs.Create(owner, 7, "invitee", types.TenantRoleContributor, &actor, "note")
		}
		record(e)
		if inv != nil {
			switch mode {
			case "duplicate":
				_, e = invs.Create(owner, 7, "invitee", types.TenantRoleContributor, &actor, "note")
				record(e)
			case "accept", "accept-twice":
				_, e = invs.Accept(invitee, inv.ID, "invitee")
				record(e)
				if mode == "accept-twice" {
					_, e = invs.Accept(invitee, inv.ID, "invitee")
					record(e)
				}
			case "wrong-invitee":
				_, e = invs.Accept(invitee, inv.ID, "other")
				record(e)
			case "expired":
				_ = db.Model(inv).Update("expires_at", time.Now().Add(-time.Hour)).Error
				_, e = invs.Accept(invitee, inv.ID, "invitee")
				record(e)
			case "decline":
				record(invs.Decline(invitee, inv.ID, "invitee"))
			case "revoke", "revoke-twice":
				record(invs.Revoke(owner, inv.ID))
				if mode == "revoke-twice" {
					record(invs.Revoke(owner, inv.ID))
				}
			case "existing-suspended":
				_ = db.Create(&types.TenantMember{TenantID: 7, UserID: "invitee", Role: types.TenantRoleAdmin, Status: types.TenantMemberStatusSuspended}).Error
				_, e = invs.Accept(invitee, inv.ID, "invitee")
				record(e)
			case "share-multi":
				_, e = invs.AcceptByToken(invitee, token, "invitee")
				record(e)
				_, e = invs.AcceptByToken(context.WithValue(invitee, types.UserIDContextKey, "other"), token, "other")
				record(e)
				_, e = invs.AcceptByToken(invitee, token, "invitee")
				record(e)
			case "share-existing":
				_, e = members.AddMember(owner, "invitee", 7, types.TenantRoleOwner, &actor)
				record(e)
				_, e = invs.AcceptByToken(invitee, token, "invitee")
				record(e)
			case "share-reconcile":
				_, e = invs.Create(owner, 7, "invitee", types.TenantRoleViewer, &actor, "direct")
				record(e)
				_, e = invs.AcceptByToken(invitee, token, "invitee")
				record(e)
			case "share-revoked":
				record(invs.Revoke(owner, inv.ID))
				_, e = invs.AcceptByToken(invitee, token, "invitee")
				record(e)
			}
		}
		var is []types.TenantInvitation
		_ = db.Order("id").Find(&is).Error
		ir := []map[string]any{}
		for _, x := range is {
			ir = append(ir, map[string]any{"id": x.ID, "invitee_user_id": x.InviteeUserID, "role": x.Role, "status": x.Status, "accepted_count": x.AcceptedCount})
		}
		var ms []types.TenantMember
		_ = db.Order("user_id").Find(&ms).Error
		mr := []map[string]any{}
		for _, x := range ms {
			mr = append(mr, map[string]any{"user_id": x.UserID, "role": x.Role, "status": x.Status, "invited_by": x.InvitedBy})
		}
		var as []types.AuditLog
		_ = db.Order("id").Find(&as).Error
		ar := []map[string]any{}
		for _, x := range as {
			var details any
			if len(x.Details) > 0 {
				_ = json.Unmarshal(x.Details, &details)
			}
			ar = append(ar, map[string]any{"actor_user_id": x.ActorUserID, "actor_role": x.ActorRole, "action": x.Action, "target_type": x.TargetType, "target_id": x.TargetID, "target_user_id": x.TargetUserID, "details": details})
		}
		outputs[mode] = map[string]any{"errors": errs, "invitations": ir, "memberships": mr, "audits": ar}
		sql, _ := db.DB()
		_ = sql.Close()
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if e := enc.Encode(outputs); e != nil {
		panic(e)
	}
}
