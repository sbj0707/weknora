package handler

import (
	"io"
	"net/http"

	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
)

type DocumentPageImageHandler struct {
	Service interfaces.DocumentPageImageService
}

func NewDocumentPageImageHandler(s interfaces.DocumentPageImageService) *DocumentPageImageHandler {
	return &DocumentPageImageHandler{Service: s}
}
func (h *DocumentPageImageHandler) State(c *gin.Context) {
	state, err := h.Service.State(c.Request.Context(), c.Param("id"), c.Query("chunk_id"))
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": true, "data": state})
}
func (h *DocumentPageImageHandler) Generate(c *gin.Context) {
	var req types.PageImageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.Error(apperrors.NewBadRequestError("页面图片请求格式错误"))
		return
	}
	state, err := h.Service.Request(c.Request.Context(), c.Param("id"), req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"success": true, "data": state})
}
func (h *DocumentPageImageHandler) Image(c *gin.Context) {
	r, mime, err := h.Service.Open(c.Request.Context(), c.Param("id"), c.Param("image_id"))
	if err != nil {
		c.Error(err)
		return
	}
	defer r.Close()
	c.Header("Cache-Control", "private, no-store")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Content-Type", mime)
	c.Status(http.StatusOK)
	io.Copy(c.Writer, r)
}
