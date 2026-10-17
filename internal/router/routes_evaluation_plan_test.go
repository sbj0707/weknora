package router

import (
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestEvaluationPlanRoutesDenyAllAPIKeys(t *testing.T) {
	gin.SetMode(gin.TestMode)
	for _, scope := range []types.TenantAPIKeyScope{{}, {KnowledgeBaseIDs: []string{"kb"}}, {Capabilities: types.StringArray{string(types.APIKeyCapabilityRunEvaluations)}}} {
		engine := gin.New()
		engine.Use(func(c *gin.Context) {
			c.Request = c.Request.WithContext(types.WithTenantAPIKeyScope(c.Request.Context(), scope))
		})
		authorizer := middleware.NewAPIKeyRouteAuthorizer()
		engine.Use(authorizer.Middleware())
		RegisterEvaluationPlanRoutes(engine.Group("/api/v1"), &handler.EvaluationPlanHandler{})
		for _, route := range []struct{ method, path string }{{"GET", ""}, {"POST", ""}, {"GET", "/id"}, {"PUT", "/id"}, {"DELETE", "/id"}, {"POST", "/id/copy"}} {
			response := httptest.NewRecorder()
			engine.ServeHTTP(response, httptest.NewRequest(route.method, "/api/v1/evaluation-plans"+route.path, nil))
			require.Equal(t, http.StatusForbidden, response.Code)
		}
	}
}
