//go:build ignore

// Regenerates codec fixtures from production Go private helpers through test-only linker seams.
package main

import (
	"encoding/json"
	"fmt"
	_ "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	_ "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/types"
	"os"
	"time"
	_ "unsafe"
)

//go:linkname invitationTTL github.com/Tencent/WeKnora/internal/application/service.invitationTTL
func invitationTTL() time.Duration

//go:linkname projectInvitation github.com/Tencent/WeKnora/internal/handler.projectInvitation
func projectInvitation(*types.TenantInvitation, map[string]*types.User, map[uint64]*types.Tenant) types.TenantInvitationResponse

//go:linkname buildInviteRegisterURL github.com/Tencent/WeKnora/internal/handler.buildInviteRegisterURL
func buildInviteRegisterURL(*config.Config, string) string
func main() {
	ttls := []map[string]any{}
	for _, raw := range []string{"", "168h", "7d", "604800", "0", "-1", "1h30m", "+0.5h", ".1s", "1.s", "1.0000000005s", "999ns", "1µs", "1μs", "1us", "1ms", "-3h", "1h-1m", " 1h", "1h ", "1e3s", "9223372036854775807ns", "9223372036854775808ns", "9223372036854775807", "9223372037", "+1", "01", "12m3.456s", "1.1234567890123456789s"} {
		_ = os.Setenv("WEKNORA_INVITATION_TTL", raw)
		ttls = append(ttls, map[string]any{"raw": raw, "nanos": int64(invitationTTL())})
	}
	urls := []map[string]any{}
	for _, a := range []string{"", "https://example.com/", " https://配置.example///\u00a0"} {
		for _, b := range []string{"", "https://env.example///", "\u2003https://环境.example/\u2003"} {
			for _, token := range []string{"", "abc_-123"} {
				_ = os.Setenv("FRONTEND_BASE_URL", b)
				urls = append(urls, map[string]any{"configured": a, "environment": b, "token": token, "output": buildInviteRegisterURL(&config.Config{FrontendBaseURL: a}, token)})
			}
		}
	}
	rows := []map[string]any{}
	actor := "owner"
	for i, ns := range []int{0, 100000000, 120000000, 123450000, 123456789} {
		for _, share := range []bool{false, true} {
			created := time.Date(2026, 1, 2, 3, 4, 5, ns, time.UTC)
			inv := &types.TenantInvitation{ID: uint64(i + 1), TenantID: 7, InviteeUserID: "invitee", InvitedBy: &actor, Role: types.TenantRoleContributor, Status: types.TenantInvitationStatusPending, ExpiresAt: created.Add(time.Hour), CreatedAt: created, Message: "note", AcceptedCount: i}
			if share {
				inv.InviteeUserID = ""
				inv.Token = "ignored-in-public-json"
			}
			users := map[string]*types.User{"owner": {ID: "owner", Username: "Owner", Email: "owner@example.com"}, "invitee": {ID: "invitee", Username: "Invitee", Email: "invitee@example.com"}}
			tenants := map[uint64]*types.Tenant{7: {ID: 7, Name: "Workspace"}}
			dbrow := map[string]any{"id": inv.ID, "tenant_id": inv.TenantID, "invitee_user_id": inv.InviteeUserID, "invited_by": actor, "role": inv.Role, "status": inv.Status, "expires_at": inv.ExpiresAt, "created_at": inv.CreatedAt, "message": inv.Message, "accepted_count": inv.AcceptedCount, "token": inv.Token}
			rows = append(rows, map[string]any{"name": fmt.Sprintf("%d-%v", ns, share), "row": dbrow, "users": users, "tenants": tenants, "output": projectInvitation(inv, users, tenants)})
		}
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if e := enc.Encode(map[string]any{"ttls": ttls, "urls": urls, "rows": rows}); e != nil {
		panic(e)
	}
}
