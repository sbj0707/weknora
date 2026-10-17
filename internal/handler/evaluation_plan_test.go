package handler

import (
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestEvaluationPlanBodyRejectsIdentityAndInvalidJSON(t *testing.T) {
	gin.SetMode(gin.TestMode)
	valid := `{"name":"Plan","qa_generation_prompt":"{{content}}","answer_comparison_prompt":"{{question}}","qa_generation_model_id":"m","evaluation_model_id":"m","scoring_dimensions":[],"pass_score":0,"qa_generation_rules":{}}`
	for _, body := range []string{"null", valid + "{}", strings.Replace(valid, `"pass_score":0,`, "", 1), strings.Replace(valid, `"pass_score":0`, `"pass_score":null`, 1), strings.Replace(valid, `"name":"Plan"`, `"name":"Plan","id":"injected"`, 1), strings.Replace(valid, `"name":"Plan"`, `"name":"Plan","created_by":"injected"`, 1), strings.Replace(valid, `"qa_generation_rules":{}`, `"qa_generation_rules":{"unknown":true}`, 1)} {
		c, _ := gin.CreateTestContext(httptest.NewRecorder())
		c.Request = httptest.NewRequest("POST", "/", strings.NewReader(body))
		_, ok := planBody(c)
		require.False(t, ok)
		require.NotEmpty(t, c.Errors)
	}
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest("POST", "/", strings.NewReader(valid))
	input, ok := planBody(c)
	require.True(t, ok)
	require.Zero(t, input.PassScore)
}
