package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/Tencent/WeKnora/internal/application/service"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
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
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.Error(apperrors.NewNotFoundError("evaluation plan not found"))
		} else if errors.As(err, &app) {
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

// List godoc
// @Summary 查询评估方案
// @Description 仅管理配置，不调用模型；仅支持用户会话，不开放 API Key。所有操作按当前空间隔离。
// @Tags 评估方案
// @Accept json
// @Produce json
// @Security Bearer
// @Param keyword query string false "GUID 或名称，最多 100 字符"
// @Param page query int false "页码，默认 1"
// @Param page_size query int false "每页数量，默认 12，上限 100"
// @Success 200 {object} types.EvaluationPlanListResponse
// @Failure 400 {object} apperrors.AppError "参数错误"
// @Failure 401 {object} apperrors.AppError "未认证"
// @Failure 403 {object} apperrors.AppError "无权操作"
// @Failure 404 {object} apperrors.AppError "方案不存在"
// @Router /evaluation-plans [get]
func (h *EvaluationPlanHandler) List(c *gin.Context) {
	var q types.EvaluationPlanQuery
	if err := c.ShouldBindQuery(&q); err != nil {
		c.Error(apperrors.NewBadRequestError("invalid pagination"))
		return
	}
	data, err := h.service.List(c.Request.Context(), q)
	planResponse(c, data, err)
}

// Get godoc
// @Summary 查看评估方案
// @Description 仅管理配置，不调用模型；仅支持用户会话，不开放 API Key。所有操作按当前空间隔离。
// @Tags 评估方案
// @Accept json
// @Produce json
// @Security Bearer
// @Param id path string true "方案 GUID"
// @Success 200 {object} types.EvaluationPlanResponse
// @Failure 400 {object} apperrors.AppError "参数错误"
// @Failure 401 {object} apperrors.AppError "未认证"
// @Failure 403 {object} apperrors.AppError "无权操作"
// @Failure 404 {object} apperrors.AppError "方案不存在"
// @Router /evaluation-plans/{id} [get]
func (h *EvaluationPlanHandler) Get(c *gin.Context) {
	data, err := h.service.Get(c.Request.Context(), c.Param("id"))
	planResponse(c, data, err)
}

// Create godoc
// @Summary 新建评估方案
// @Description 仅管理配置，不调用模型；仅支持用户会话，不开放 API Key。所有操作按当前空间隔离。
// @Tags 评估方案
// @Accept json
// @Produce json
// @Security Bearer
// @Param request body types.EvaluationPlanInput true "完整方案配置"
// @Success 200 {object} types.EvaluationPlanResponse
// @Failure 400 {object} apperrors.AppError "参数错误"
// @Failure 401 {object} apperrors.AppError "未认证"
// @Failure 403 {object} apperrors.AppError "无权操作"
// @Failure 404 {object} apperrors.AppError "方案不存在"
// @Router /evaluation-plans [post]
func (h *EvaluationPlanHandler) Create(c *gin.Context) {
	input, ok := planBody(c)
	if !ok {
		return
	}
	data, err := h.service.Create(c.Request.Context(), input)
	planResponse(c, data, err)
}

// Update godoc
// @Summary 编辑评估方案
// @Description 仅管理配置，不调用模型；仅支持用户会话，不开放 API Key。所有操作按当前空间隔离。
// @Tags 评估方案
// @Accept json
// @Produce json
// @Security Bearer
// @Param id path string true "方案 GUID"
// @Param request body types.EvaluationPlanInput true "完整方案配置"
// @Success 200 {object} types.EvaluationPlanResponse
// @Failure 400 {object} apperrors.AppError "参数错误"
// @Failure 401 {object} apperrors.AppError "未认证"
// @Failure 403 {object} apperrors.AppError "无权操作"
// @Failure 404 {object} apperrors.AppError "方案不存在"
// @Router /evaluation-plans/{id} [put]
func (h *EvaluationPlanHandler) Update(c *gin.Context) {
	input, ok := planBody(c)
	if !ok {
		return
	}
	data, err := h.service.Update(c.Request.Context(), c.Param("id"), input)
	planResponse(c, data, err)
}

// Delete godoc
// @Summary 删除评估方案
// @Description 仅管理配置，不调用模型；仅支持用户会话，不开放 API Key。所有操作按当前空间隔离。
// @Tags 评估方案
// @Accept json
// @Produce json
// @Security Bearer
// @Param id path string true "方案 GUID"
// @Success 200 {object} map[string]interface{}
// @Failure 400 {object} apperrors.AppError "参数错误"
// @Failure 401 {object} apperrors.AppError "未认证"
// @Failure 403 {object} apperrors.AppError "无权操作"
// @Failure 404 {object} apperrors.AppError "方案不存在"
// @Router /evaluation-plans/{id} [delete]
func (h *EvaluationPlanHandler) Delete(c *gin.Context) {
	err := h.service.Delete(c.Request.Context(), c.Param("id"))
	planResponse(c, nil, err)
}

// Copy godoc
// @Summary 复制评估方案
// @Description 仅管理配置，不调用模型；仅支持用户会话，不开放 API Key。所有操作按当前空间隔离。
// @Tags 评估方案
// @Accept json
// @Produce json
// @Security Bearer
// @Param id path string true "方案 GUID"
// @Success 200 {object} types.EvaluationPlanResponse
// @Failure 400 {object} apperrors.AppError "参数错误"
// @Failure 401 {object} apperrors.AppError "未认证"
// @Failure 403 {object} apperrors.AppError "无权操作"
// @Failure 404 {object} apperrors.AppError "方案不存在"
// @Router /evaluation-plans/{id}/copy [post]
func (h *EvaluationPlanHandler) Copy(c *gin.Context) {
	data, err := h.service.Copy(c.Request.Context(), c.Param("id"))
	planResponse(c, data, err)
}
