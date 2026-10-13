package handler

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/Tencent/WeKnora/internal/application/service"
	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
)

// GetChunkDetail godoc
// @Summary 获取文档分块的完整内容和索引详情
// @Tags 知识管理
// @Produce json
// @Param id path string true "知识ID"
// @Param chunk_id path string true "分块ID"
// @Success 200 {object} map[string]interface{}
// @Security Bearer
// @Security ApiKeyAuth
// @Router /knowledge/{id}/chunks/{chunk_id}/details [get]
func (h *KnowledgeHandler) GetChunkDetail(c *gin.Context) {
	knowledge, ctx, err := h.resolveKnowledgeAndValidateKBAccess(c, c.Param("id"), types.OrgRoleViewer)
	if err != nil {
		c.Error(err)
		return
	}
	provider, ok := h.kgService.(interfaces.ChunkDetailService)
	if !ok {
		c.Error(apperrors.NewInternalServerError("Chunk details unavailable"))
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	detail, err := provider.GetChunkDetail(ctx, knowledge, c.Param("chunk_id"))
	if err != nil {
		if errors.Is(err, service.ErrChunkNotFound) {
			c.Error(apperrors.NewNotFoundError("Chunk not found"))
		} else {
			c.Error(apperrors.NewInternalServerError("Chunk details unavailable"))
		}
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": true, "data": detail})
}
