package handler

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
)

type chunkDetailHandlerStub struct {
	handlerKnowledgeAccessStub
	calls  int
	tenant uint64
	err    error
}

func (s *chunkDetailHandlerStub) GetChunkDetail(ctx context.Context, doc *types.Knowledge, chunkID string) (*types.ChunkDetail, error) {
	s.calls++
	s.tenant = types.MustTenantIDFromContext(ctx)
	if s.err != nil {
		return nil, s.err
	}
	return &types.ChunkDetail{Chunk: &types.Chunk{ID: chunkID, KnowledgeID: doc.ID}}, nil
}
func TestChunkDetailHandlerSharedOwnerAndScope(t *testing.T) {
	gin.SetMode(gin.TestMode)
	for _, tt := range []struct {
		name  string
		scope bool
		err   error
		code  int
	}{
		{name: "shared viewer", code: 200}, {name: "scope denied", scope: true, code: 403}, {name: "foreign document chunk", err: service.ErrChunkNotFound, code: 404},
	} {
		t.Run(tt.name, func(t *testing.T) {
			kg := &chunkDetailHandlerStub{handlerKnowledgeAccessStub: handlerKnowledgeAccessStub{knowledge: &types.Knowledge{ID: "doc", KnowledgeBaseID: "kb", TenantID: 2}}, err: tt.err}
			kb := &stubKBOnlyService{getByID: func(context.Context, string) (*types.KnowledgeBase, error) {
				return &types.KnowledgeBase{ID: "kb", TenantID: 2}, nil
			}}
			h := &KnowledgeHandler{kgService: kg, kbService: kb, kbShareService: &handlerKBShareStub{}}
			r := gin.New()
			r.Use(middleware.ErrorHandler(), func(c *gin.Context) {
				ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, uint64(1))
				ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRoleViewer)
				if tt.scope {
					ctx = types.WithTenantAPIKeyScope(ctx, types.TenantAPIKeyScope{KnowledgeBaseIDs: types.StringArray{"other"}})
				}
				c.Request = c.Request.WithContext(ctx)
				c.Set(types.TenantIDContextKey.String(), uint64(1))
				c.Next()
			})
			r.GET("/:id/chunks/:chunk_id/details", h.GetChunkDetail)
			w := httptest.NewRecorder()
			r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/doc/chunks/chunk/details", nil))
			require.Equal(t, tt.code, w.Code, w.Body.String())
			if tt.scope {
				require.Zero(t, kg.calls)
			} else {
				require.Equal(t, uint64(2), kg.tenant)
			}
		})
	}
}
