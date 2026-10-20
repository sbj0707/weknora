package docparser

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/Tencent/WeKnora/docreader/proto"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

const pageRenderTimeout = 90 * time.Second

// OpenPDF reuses DocReader's connection, TLS, auth and reconnect policy.
// Bounded upload frames avoid the existing unary message-size cap.
func (p *GRPCDocumentReader) OpenPDF(ctx context.Context, source io.Reader) (interfaces.PDFPageSession, error) {
	p.mu.RLock()
	client := p.client
	p.mu.RUnlock()
	if client == nil {
		return nil, errNotConnected
	}
	streamCtx, cancel := context.WithCancel(ctx)
	timer := time.AfterFunc(pageRenderTimeout, cancel)
	defer timer.Stop()
	stream, err := client.RenderPages(streamCtx)
	if err != nil {
		cancel()
		return nil, pageRenderError(err)
	}
	session := &pdfPageSession{stream: stream, cancel: cancel}
	fail := func(err error) (interfaces.PDFPageSession, error) {
		if errors.Is(err, io.EOF) {
			_, err = stream.Recv()
		}
		session.Close()
		return nil, pageRenderError(err)
	}
	buf := make([]byte, 1<<20)
	var total int64
	for {
		n, readErr := source.Read(buf)
		if n > 0 {
			total += int64(n)
			if total > 256<<20 {
				return fail(fmt.Errorf("PDF 超过页图处理限制（256 MiB）"))
			}
			if err := stream.Send(&proto.RenderPagesRequest{Payload: &proto.RenderPagesRequest_FileChunk{FileChunk: buf[:n]}}); err != nil {
				return fail(err)
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return fail(readErr)
		}
	}
	if err := stream.Send(&proto.RenderPagesRequest{Payload: &proto.RenderPagesRequest_FinishUpload{FinishUpload: true}}); err != nil {
		return fail(err)
	}
	frame, err := stream.Recv()
	if err != nil {
		return fail(err)
	}
	info := frame.GetInfo()
	if info == nil || info.PageCount < 1 || info.PageCount > 5000 || info.RenderProfile != types.PageImageRenderProfile {
		return fail(fmt.Errorf("DocReader 返回无效页图信息或不兼容的渲染规格"))
	}
	session.count = int(info.PageCount)
	return session, nil
}

func pageRenderError(err error) error {
	if status.Code(err) == codes.Unimplemented {
		return fmt.Errorf("DocReader 不支持页面图片渲染，请升级 DocReader 服务后重试: %w", err)
	}
	return fmt.Errorf("DocReader 页面图片渲染失败: %w", err)
}

type pdfPageSession struct {
	mu     sync.Mutex
	stream grpc.BidiStreamingClient[proto.RenderPagesRequest, proto.RenderPagesResponse]
	cancel context.CancelFunc
	count  int
}

func (s *pdfPageSession) PageCount() int { return s.count }

func (s *pdfPageSession) Render(ctx context.Context, page int) ([]byte, int, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if page < 1 || page > s.count {
		return nil, 0, 0, fmt.Errorf("页面超出原文范围")
	}
	if err := ctx.Err(); err != nil {
		return nil, 0, 0, err
	}
	timer := time.AfterFunc(pageRenderTimeout, s.cancel)
	defer timer.Stop()
	stop := context.AfterFunc(ctx, s.cancel)
	defer stop()
	err := s.stream.Send(&proto.RenderPagesRequest{Payload: &proto.RenderPagesRequest_PageNumber{PageNumber: uint32(page)}})
	if err != nil {
		if errors.Is(err, io.EOF) {
			_, err = s.stream.Recv()
		}
		return nil, 0, 0, pageRenderError(err)
	}
	frame, err := s.stream.Recv()
	if err != nil {
		return nil, 0, 0, pageRenderError(err)
	}
	image := frame.GetPage()
	if image == nil || int(image.PageNumber) != page {
		return nil, 0, 0, fmt.Errorf("DocReader 返回的页图与请求页码不一致")
	}
	if image.Error != "" {
		return nil, 0, 0, fmt.Errorf("DocReader 页面渲染失败: %s", image.Error)
	}
	if len(image.ImageData) == 0 || len(image.ImageData) > 4<<20 || image.Width == 0 || image.Height == 0 || image.Width > 2000 || image.Height > 2000 {
		return nil, 0, 0, fmt.Errorf("DocReader 返回的页图尺寸或大小无效")
	}
	return image.ImageData, int(image.Width), int(image.Height), nil
}

func (s *pdfPageSession) Close() error {
	s.cancel()
	return s.stream.CloseSend()
}
