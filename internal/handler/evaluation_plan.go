package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/application/service"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/gin-gonic/gin"
	"io"
	"net/http"
)

type EvaluationPlanHandler struct {
	service *service.EvaluationPlanService
}

func NewEvaluationPlanHandler(s *service.EvaluationPlanService) *EvaluationPlanHandler {
	return &EvaluationPlanHandler{service: s}
}
func planResponse(c *gin.Context, data any, err error) {
	if err != nil {
		var app *apperrors.AppError
		if errors.As(err, &app) {
			c.Error(app)
		} else {
			c.Error(apperrors.NewInternalServerError("evaluation plan operation failed"))
		}
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": true, "data": data})
}
func planBody(c *gin.Context) (types.EvaluationPlanInput, bool) {
	var input types.EvaluationPlanInput
	// Decode an explicit allowlist of editable fields, including nested objects.
	// Never bind ID, tenant, owner or audit metadata from client input.
	body := http.MaxBytesReader(c.Writer, c.Request.Body, 1024*1024)
	raw, err := io.ReadAll(body)
	var fields map[string]json.RawMessage
	if err == nil {
		err = json.Unmarshal(raw, &fields)
	}
	if err != nil || fields == nil {
		c.Error(apperrors.NewBadRequestError("invalid plan body"))
		return input, false
	}
	for _, field := range []string{"name", "qa_generation_prompt", "answer_comparison_prompt", "qa_generation_model_id", "evaluation_model_id", "scoring_dimensions", "pass_score", "qa_generation_rules"} {
		if v, ok := fields[field]; !ok || string(v) == "null" {
			c.Error(apperrors.NewBadRequestError("missing required plan field").WithDetails(map[string]string{"field": field}))
			return input, false
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&input); err != nil {
		c.Error(apperrors.NewBadRequestError("invalid or non-editable plan field"))
		return input, false
	}
	return input, true
}
func (h *EvaluationPlanHandler) List(c *gin.Context) {
	var q types.EvaluationPlanQuery
	if err := c.ShouldBindQuery(&q); err != nil {
		c.Error(apperrors.NewBadRequestError("invalid pagination"))
		return
	}
	data, err := h.service.List(c.Request.Context(), q)
	planResponse(c, data, err)
}
func (h *EvaluationPlanHandler) Get(c *gin.Context) {
	data, err := h.service.Get(c.Request.Context(), c.Param("id"))
	planResponse(c, data, err)
}
func (h *EvaluationPlanHandler) Create(c *gin.Context) {
	input, ok := planBody(c)
	if !ok {
		return
	}
	data, err := h.service.Create(c.Request.Context(), input)
	planResponse(c, data, err)
}
func (h *EvaluationPlanHandler) Update(c *gin.Context) {
	input, ok := planBody(c)
	if !ok {
		return
	}
	data, err := h.service.Update(c.Request.Context(), c.Param("id"), input)
	planResponse(c, data, err)
}
func (h *EvaluationPlanHandler) Delete(c *gin.Context) {
	err := h.service.Delete(c.Request.Context(), c.Param("id"))
	planResponse(c, nil, err)
}
func (h *EvaluationPlanHandler) Copy(c *gin.Context) {
	data, err := h.service.Copy(c.Request.Context(), c.Param("id"))
	planResponse(c, data, err)
}
