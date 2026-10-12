package handler

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
)

type indexStatusHandlerStub struct {
	handlerKnowledgeAccessStub
	calls  int
	tenant uint64
}

func (s *indexStatusHandlerStub) GetKnowledgeIndexStatus(ctx context.Context, k *types.Knowledge, _ []types.KnowledgeProcessingSpan) (*types.KnowledgeIndexStatus, error) {
	s.calls++
	s.tenant = types.MustTenantIDFromContext(ctx)
	return &types.KnowledgeIndexStatus{KnowledgeID: k.ID}, nil
}

func TestKnowledgeIndexStatusHandlerAccess(t *testing.T) {
	gin.SetMode(gin.TestMode)
	for _, scopedOut := range []bool{false, true} {
		t.Run(map[bool]string{false: "shared viewer", true: "API key scope denied"}[scopedOut], func(t *testing.T) {
			kg := &indexStatusHandlerStub{handlerKnowledgeAccessStub: handlerKnowledgeAccessStub{
				knowledge: &types.Knowledge{ID: "doc", KnowledgeBaseID: "kb", TenantID: 2},
			}}
			kb := &stubKBOnlyService{getByID: func(context.Context, string) (*types.KnowledgeBase, error) {
				return &types.KnowledgeBase{ID: "kb", TenantID: 2}, nil
			}}
			h := &KnowledgeHandler{kgService: kg, kbService: kb, kbShareService: &handlerKBShareStub{}}
			r := gin.New()
			r.Use(middleware.ErrorHandler(), func(c *gin.Context) {
				ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, uint64(1))
				ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRoleViewer)
				if scopedOut {
					ctx = types.WithTenantAPIKeyScope(ctx, types.TenantAPIKeyScope{KnowledgeBaseIDs: types.StringArray{"other"}})
				}
				c.Request = c.Request.WithContext(ctx)
				c.Set(types.TenantIDContextKey.String(), uint64(1))
				c.Next()
			})
			r.GET("/:id/index-status", h.GetKnowledgeIndexStatus)
			w := httptest.NewRecorder()
			r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/doc/index-status", nil))
			if scopedOut {
				require.Equal(t, http.StatusForbidden, w.Code, w.Body.String())
				require.Zero(t, kg.calls)
			} else {
				require.Equal(t, http.StatusOK, w.Code, w.Body.String())
				require.Equal(t, 1, kg.calls)
				require.Equal(t, uint64(2), kg.tenant)
			}
		})
	}
}
