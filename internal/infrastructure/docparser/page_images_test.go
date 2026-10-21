package docparser

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"net"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Tencent/WeKnora/docreader/proto"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/grpc/test/bufconn"
)

type pageImageServer struct {
	proto.UnimplementedDocReaderServer
	uploaded  atomic.Int64
	pages     atomic.Int64
	closed    chan struct{}
	wrongPage bool
	failPage  bool
	profile   string
}

func (s *pageImageServer) RenderPages(stream grpc.BidiStreamingServer[proto.RenderPagesRequest, proto.RenderPagesResponse]) error {
	defer close(s.closed)
	for {
		frame, err := stream.Recv()
		if err != nil {
			return err
		}
		if frame.GetFinishUpload() {
			break
		}
		if len(frame.GetFileChunk()) > 1<<20 {
			return status.Error(codes.InvalidArgument, "oversized upload chunk")
		}
		s.uploaded.Add(int64(len(frame.GetFileChunk())))
	}
	profile := s.profile
	if profile == "" {
		profile = types.PageImageRenderProfile
	}
	if err := stream.Send(&proto.RenderPagesResponse{Payload: &proto.RenderPagesResponse_Info{Info: &proto.PDFPageInfo{PageCount: 2, RenderProfile: profile}}}); err != nil {
		return err
	}
	for {
		frame, err := stream.Recv()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		number := frame.GetPageNumber()
		s.pages.Add(1)
		if s.wrongPage {
			number++
		}
		img := image.NewRGBA(image.Rect(0, 0, 16, 24))
		img.Set(0, 0, color.White)
		var encoded bytes.Buffer
		_ = jpeg.Encode(&encoded, img, nil)
		page := &proto.RenderedPage{PageNumber: number, ImageData: encoded.Bytes(), Width: 16, Height: 24}
		if s.failPage {
			page.Error = "fixture page failure"
		}
		if err := stream.Send(&proto.RenderPagesResponse{Payload: &proto.RenderPagesResponse_Page{Page: page}}); err != nil {
			return err
		}
	}
}

func newPageReader(t *testing.T, impl proto.DocReaderServer) *GRPCDocumentReader {
	t.Helper()
	listener := bufconn.Listen(2 << 20)
	server := grpc.NewServer(grpc.MaxRecvMsgSize(2 << 20))
	proto.RegisterDocReaderServer(server, impl)
	go server.Serve(listener)
	conn, err := grpc.NewClient("passthrough:///pages", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) { return listener.Dial() }))
	require.NoError(t, err)
	t.Cleanup(func() { conn.Close(); server.Stop(); listener.Close() })
	return &GRPCDocumentReader{conn: conn, client: proto.NewDocReaderClient(conn)}
}

func TestPDFSessionUploadsOnceAcrossPageRenders(t *testing.T) {
	server := &pageImageServer{closed: make(chan struct{})}
	reader := newPageReader(t, server)
	source := bytes.Repeat([]byte("p"), 3<<20)
	session, err := reader.OpenPDF(context.Background(), bytes.NewReader(source))
	require.NoError(t, err)
	require.Equal(t, 2, session.PageCount())
	for _, page := range []int{2, 1, 2} {
		data, w, h, err := session.Render(context.Background(), page)
		require.NoError(t, err)
		decoded, err := jpeg.Decode(bytes.NewReader(data))
		require.NoError(t, err)
		require.Equal(t, w, decoded.Bounds().Dx())
		require.Equal(t, h, decoded.Bounds().Dy())
	}
	require.EqualValues(t, len(source), server.uploaded.Load(), "PDF must not be re-uploaded per page")
	require.EqualValues(t, 3, server.pages.Load())
	session.Close()
	select {
	case <-server.closed:
	case <-time.After(3 * time.Second):
		t.Fatal("RPC session was not released")
	}
}

func TestPDFSessionRejectsMismatchedPagesAndFailureFrames(t *testing.T) {
	for _, test := range []struct {
		name    string
		server  *pageImageServer
		message string
	}{
		{"wrong page", &pageImageServer{wrongPage: true}, "页码不一致"},
		{"page failure", &pageImageServer{failPage: true}, "fixture page failure"},
	} {
		t.Run(test.name, func(t *testing.T) {
			test.server.closed = make(chan struct{})
			session, err := newPageReader(t, test.server).OpenPDF(context.Background(), bytes.NewReader([]byte("pdf")))
			require.NoError(t, err)
			defer session.Close()
			_, _, _, err = session.Render(context.Background(), 1)
			require.ErrorContains(t, err, test.message)
			_, _, _, err = session.Render(context.Background(), 3)
			require.ErrorContains(t, err, "范围")
		})
	}
}

func TestPDFSessionOldDocReaderHasActionableUpgradeError(t *testing.T) {
	_, err := newPageReader(t, &proto.UnimplementedDocReaderServer{}).OpenPDF(context.Background(), bytes.NewReader([]byte("pdf")))
	require.ErrorContains(t, err, "升级 DocReader")
	require.Equal(t, codes.Unimplemented, status.Code(err))
}

func TestPDFSessionRejectsIncompatibleProfile(t *testing.T) {
	server := &pageImageServer{closed: make(chan struct{}), profile: "unknown-profile"}
	_, err := newPageReader(t, server).OpenPDF(context.Background(), bytes.NewReader([]byte("pdf")))
	require.ErrorContains(t, err, "渲染规格")
}

func TestPDFSessionCancellationStopsRemoteWork(t *testing.T) {
	server := &pageImageServer{closed: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	session, err := newPageReader(t, server).OpenPDF(ctx, bytes.NewReader([]byte("pdf")))
	require.NoError(t, err)
	defer session.Close()
	cancel()
	select {
	case <-server.closed:
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not reach DocReader")
	}
}

// Optional integration against a freshly started DocReader; Python never runs
// inside the Go app. The fixture contains distinct native vector PDF pages.
func TestDocReaderNativePageRenderIntegration(t *testing.T) {
	address := os.Getenv("DOCREADER_PAGE_IMAGE_TEST_ADDR")
	if address == "" {
		t.Skip("set DOCREADER_PAGE_IMAGE_TEST_ADDR to run against DocReader")
	}
	reader, err := NewGRPCDocumentReader(address)
	require.NoError(t, err)
	defer reader.Close()
	file, err := os.Open("../../pageimage/testdata/pages.pdf")
	require.NoError(t, err)
	defer file.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	session, err := reader.OpenPDF(ctx, file)
	require.NoError(t, err)
	defer session.Close()
	require.Equal(t, 2, session.PageCount())
	data, w, h, err := session.Render(ctx, 2)
	require.NoError(t, err)
	require.Equal(t, 500, w)
	require.Equal(t, 250, h)
	img, err := jpeg.Decode(bytes.NewReader(data))
	require.NoError(t, err)
	red, _, blue, _ := img.At(10, 10).RGBA()
	require.Greater(t, blue, red)
}
