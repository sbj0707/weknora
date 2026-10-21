package router

import (
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/gin-gonic/gin"
)

func RegisterDocumentPageImageRoutes(r *gin.RouterGroup, h *handler.DocumentPageImageHandler, g *rbacGuards) {
	if h == nil {
		return
	}
	write := g.apiKeyGroup(r.Group("/knowledge"), apiKeyIngest(apiKeyFullAccess()))
	read := write.With(apiKeyRetrieve(apiKeyFullAccess()))
	read.GET("/:id/page-images", g.Viewer(), g.KBAccessReadFromKnowledgeIDParam("id"), h.State)
	read.GET("/:id/page-images/:image_id", g.Viewer(), g.KBAccessReadFromKnowledgeIDParam("id"), h.Image)
	write.POST("/:id/page-images", g.OwnedKnowledgeKBOrAdmin(), g.KBAccessWriteFromKnowledgeIDParam("id"), h.Generate)
}
