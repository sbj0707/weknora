package router

import (
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/gin-gonic/gin"
)

// Plan configuration is user-session only. Deliberately do not declare an API
// key policy: the APIKeyGate denies full and KB-restricted keys alike. Tenant
// and creator/admin permissions are enforced by the service on every operation.
func RegisterEvaluationPlanRoutes(r *gin.RouterGroup, h *handler.EvaluationPlanHandler) {
	plans := r.Group("/evaluation-plans")
	plans.GET("", h.List)
	plans.POST("", h.Create)
	plans.GET("/:id", h.Get)
	plans.PUT("/:id", h.Update)
	plans.DELETE("/:id", h.Delete)
	plans.POST("/:id/copy", h.Copy)
}
