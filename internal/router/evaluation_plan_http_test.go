package router

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type planHTTPModels struct{ interfaces.ModelRepository }

func (planHTTPModels) GetByID(context.Context, uint64, string) (*types.Model, error) {
	return &types.Model{ID: "chat", TenantID: 1, Type: types.ModelTypeKnowledgeQA, Status: types.ModelStatusActive}, nil
}
func TestEvaluationPlanHTTPContract(t *testing.T) {
	gin.SetMode(gin.TestMode)
	db, err := gorm.Open(sqlite.Open(filepath.Join(t.TempDir(), "http.db")), &gorm.Config{})
	require.NoError(t, err)
	sqlDB, err := db.DB()
	require.NoError(t, err)
	defer sqlDB.Close()
	ddl, err := os.ReadFile("../../migrations/sqlite/000034_evaluation_plans.up.sql")
	require.NoError(t, err)
	require.NoError(t, db.Exec(string(ddl)).Error)
	require.NoError(t, db.Exec("CREATE TABLE users(id TEXT,username TEXT,deleted_at DATETIME)").Error)
	svc := service.NewEvaluationPlanService(repository.NewEvaluationPlanRepository(db), planHTTPModels{}, nil, nil)
	engine := gin.New()
	engine.Use(middleware.ErrorHandler())
	engine.Use(func(c *gin.Context) {
		tenant := uint64(1)
		if c.GetHeader("Test-Tenant") == "foreign" {
			tenant = 2
		}
		role := types.TenantRoleContributor
		if c.GetHeader("Test-Role") == "viewer" {
			role = types.TenantRoleViewer
		}
		ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
		c.Request = c.Request.WithContext(types.WithCaller(ctx, types.Caller{TenantID: tenant, UserID: "alice", Role: role}))
	})
	RegisterEvaluationPlanRoutes(engine.Group("/api/v1"), handler.NewEvaluationPlanHandler(svc))
	request := func(method, path string, body any, headers map[string]string) (int, map[string]any) {
		var raw []byte
		if body != nil {
			raw, err = json.Marshal(body)
			require.NoError(t, err)
		}
		r := httptest.NewRequest(method, "/api/v1/evaluation-plans"+path, bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		w := httptest.NewRecorder()
		engine.ServeHTTP(w, r)
		var response map[string]any
		require.NoError(t, json.Unmarshal(w.Body.Bytes(), &response))
		return w.Code, response
	}
	input := map[string]any{"name": "HTTP Plan", "description": "", "qa_generation_prompt": " {{content}} ", "answer_comparison_prompt": "{{actual_answer}}", "qa_generation_model_id": "chat", "evaluation_model_id": "chat", "scoring_dimensions": []any{map[string]any{"id": "accuracy", "name": "Accuracy", "description": "", "weight": 100}}, "pass_score": 80, "qa_generation_rules": map[string]any{"questions_per_chunk": 3, "question_types": []string{"fact"}, "difficulty": "medium", "language": "zh-CN"}}
	status, _ := request("POST", "", input, map[string]string{"Test-Role": "viewer"})
	require.Equal(t, 403, status)
	input["tenant_id"] = 2
	status, _ = request("POST", "", input, nil)
	require.Equal(t, 400, status)
	delete(input, "tenant_id")
	status, created := request("POST", "", input, nil)
	require.Equal(t, 200, status)
	p := created["data"].(map[string]any)
	id := p["id"].(string)
	require.Equal(t, "alice", p["created_by"])
	status, list := request("GET", "?page=1&page_size=12&keyword=HTTP", nil, nil)
	require.Equal(t, 200, status)
	require.Equal(t, float64(1), list["data"].(map[string]any)["total"])
	status, _ = request("GET", "/"+id, nil, map[string]string{"Test-Tenant": "foreign"})
	require.Equal(t, 404, status)
	input["pass_score"] = 0
	status, updated := request("PUT", "/"+id, input, nil)
	require.Equal(t, 200, status)
	require.Equal(t, float64(0), updated["data"].(map[string]any)["pass_score"])
	status, copy := request("POST", "/"+id+"/copy", nil, nil)
	require.Equal(t, 200, status)
	require.NotEqual(t, id, copy["data"].(map[string]any)["id"])
	status, _ = request("DELETE", "/"+id, nil, nil)
	require.Equal(t, 200, status)
	status, _ = request("GET", "/"+id, nil, nil)
	require.Equal(t, 404, status)
	status, _ = request("GET", "?page_size=bad", nil, nil)
	require.Equal(t, 400, status)
}
