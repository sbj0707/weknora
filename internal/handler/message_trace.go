package handler

import (
	"errors"
	"net/http"

	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/tracing/langfuse"
	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

// GetTrace reads one turn's trace only after the normal tenant/session ownership
// check. Trace IDs and Langfuse credentials are never accepted from the client.
func (h *MessageHandler) GetTrace(c *gin.Context) {
	ctx := c.Request.Context()
	message, err := h.MessageService.GetMessage(ctx, c.Param("session_id"), c.Param("id"))
	if err != nil {
		if errors.Is(err, apperrors.ErrSessionNotFound) || errors.Is(err, gorm.ErrRecordNotFound) {
			_ = c.Error(apperrors.NewNotFoundError("message not found"))
		} else {
			_ = c.Error(apperrors.NewInternalServerError("cannot load message"))
		}
		return
	}
	reader := h.TraceReader
	if reader == nil {
		reader = langfuse.GetManager().FindMessageTrace
	}
	trace, err := reader(ctx, message)
	if err != nil && !errors.Is(err, langfuse.ErrTraceUnavailable) {
		logger.Warnf(ctx, "Cannot read message trace: %v", err)
		_ = c.Error(apperrors.NewServiceUnavailableError("cannot load conversation trace"))
		return
	}
	state := "ready"
	if errors.Is(err, langfuse.ErrTraceUnavailable) {
		state = "disabled"
	} else if trace == nil {
		state = "not_found"
	}
	c.JSON(http.StatusOK, gin.H{"success": true, "data": gin.H{
		"state": state, "session_id": message.SessionID, "message_id": message.ID,
		"request_id": message.RequestID, "trace": trace,
	}})
}
