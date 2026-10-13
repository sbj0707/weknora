//go:build ignore

// go run backend-java/src/test/java/com/tencent/weknora/security/member_golden.go > backend-java/src/test/java/com/tencent/weknora/security/member-golden.json
package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/types"
	"os"
	"time"
)

func main() {
	actor := "u"
	row := types.TenantMemberResponse{UserID: "t", Email: "t@example.com", Username: "Target", Role: types.TenantRoleViewer, Status: types.TenantMemberStatusActive, InvitedBy: &actor, JoinedAt: time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)}
	bare := types.TenantMemberResponse{UserID: "missing-user", Role: types.TenantRoleContributor, Status: types.TenantMemberStatusSuspended, JoinedAt: row.JoinedAt}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(map[string]any{"member": row, "dangling": bare, "role_details": map[string]string{"old_role": "viewer", "new_role": "admin"}, "last_owner_error": "cannot demote or remove the last active owner of the tenant"})
}
