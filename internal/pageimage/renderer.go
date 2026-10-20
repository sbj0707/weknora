package pageimage

import (
	"context"
	"fmt"
	"os"

	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

// Rendering belongs to DocReader; there is no local Python fallback.
func (s *Service) openPDF(ctx context.Context, path string) (interfaces.PDFPageSession, error) {
	if s.Renderer == nil {
		return nil, fmt.Errorf("页图渲染需要支持 RenderPages 的 DocReader gRPC 服务，请检查 DOCREADER_TRANSPORT 和 DocReader 版本")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	return s.Renderer.OpenPDF(ctx, file)
}
