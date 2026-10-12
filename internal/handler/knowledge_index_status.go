package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

import "github.com/gin-gonic/gin"

// GetKnowledgeIndexStatus godoc
// @Summary 获取文档各类索引的构建状态与实际数量
// @Tags 知识管理
// @Produce json
// @Param id path string true "知识ID"
// @Success 200 {object} map[string]interface{}
// @Security Bearer
// @Security ApiKeyAuth
// @Router /knowledge/{id}/index-status [get]
func (h *KnowledgeHandler) GetKnowledgeIndexStatus(c *gin.Context) {
	knowledge, ctx, err := h.resolveKnowledgeAndValidateKBAccess(c, c.Param("id"), types.OrgRoleViewer)
	if err != nil {
		c.Error(err)
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	var spans []types.KnowledgeProcessingSpan
	if h.spanRepo != nil {
		attempt, err := h.spanRepo.LatestAttempt(ctx, knowledge.ID)
		if err == nil && attempt > 0 {
			spans, err = h.spanRepo.ListByAttempt(ctx, knowledge.ID, attempt)
		}
		if err != nil {
			logger.Warnf(ctx, "document index history unavailable: %v", err)
			spans = nil
		}
	}
	provider, ok := h.kgService.(interfaces.KnowledgeIndexStatusService)
	if !ok {
		c.Error(errors.NewInternalServerError("Document index statistics unavailable"))
		return
	}
	report, err := provider.GetKnowledgeIndexStatus(ctx, knowledge, spans)
	if err != nil {
		c.Error(errors.NewInternalServerError("Document index statistics unavailable"))
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": true, "data": report})
}
