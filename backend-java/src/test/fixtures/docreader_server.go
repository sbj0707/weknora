//go:build ignore

// Local interop test double using the unchanged production Go gRPC protocol.
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"

	"github.com/Tencent/WeKnora/docreader/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type reader struct {
	proto.UnimplementedDocReaderServer
}

func authorized(ctx context.Context) error {
	md, _ := metadata.FromIncomingContext(ctx)
	if values := md.Get("authorization"); len(values) != 1 || values[0] != "Bearer interop-test-token" {
		return status.Error(codes.Unauthenticated, "test fixture requires bearer metadata")
	}
	return nil
}
func response(req *proto.ReadRequest) (*proto.ReadResponse, error) {
	if req.FileName != "文档.pdf" || req.RequestId != "request-42" || string(req.FileContent) != string([]byte{0, 1, 255}) || req.GetConfig().GetParserEngineOverrides()["ocr"] != "true" {
		return nil, status.Error(codes.InvalidArgument, "request differs from canonical fixture")
	}
	return &proto.ReadResponse{MarkdownContent: "Go→Java 😀中文", ImageDirPath: "/tmp/go-images", Metadata: map[string]string{"parser": req.Config.ParserEngine, "request_id": req.RequestId}, ImageRefs: []*proto.ImageRef{{Filename: "page.png", MimeType: "image/png", ImageData: []byte{0, 1, 255}}}, SourceBlocks: []*proto.SourceBlock{{Start: 0, End: 2, LocatorJson: `{"type":"pdf","page":3}`}}}, nil
}
func (r *reader) Read(ctx context.Context, req *proto.ReadRequest) (*proto.ReadResponse, error) {
	if err := authorized(ctx); err != nil {
		return nil, err
	}
	return response(req)
}
func (r *reader) ReadStream(req *proto.ReadRequest, stream grpc.ServerStreamingServer[proto.ReadStreamResponse]) error {
	if err := authorized(stream.Context()); err != nil {
		return err
	}
	if req.GetConfig().GetParserEngine() == "legacy" {
		return status.Error(codes.Unimplemented, "legacy test server")
	}
	result, err := response(req)
	if err != nil {
		return err
	}
	if err := stream.Send(&proto.ReadStreamResponse{Payload: &proto.ReadStreamResponse_Meta{Meta: &proto.ReadStreamMeta{MarkdownContent: result.MarkdownContent, ImageDirPath: result.ImageDirPath, Metadata: result.Metadata, ImageCount: uint32(len(result.ImageRefs)), SourceBlocks: result.SourceBlocks}}}); err != nil {
		return err
	}
	for _, image := range result.ImageRefs {
		if err := stream.Send(&proto.ReadStreamResponse{Payload: &proto.ReadStreamResponse_Image{Image: image}}); err != nil {
			return err
		}
	}
	return nil
}
func (r *reader) ListEngines(ctx context.Context, req *proto.ListEnginesRequest) (*proto.ListEnginesResponse, error) {
	if err := authorized(ctx); err != nil {
		return nil, err
	}
	return &proto.ListEnginesResponse{Engines: []*proto.ParserEngineInfo{{Name: "builtin", Description: req.ConfigOverrides["description"], FileTypes: []string{"pdf", "docx"}, Available: true}}}, nil
}
func main() {
	cert := flag.String("tls-cert", "", "server certificate")
	key := flag.String("tls-key", "", "server key")
	ca := flag.String("client-ca", "", "require trusted client certificate")
	flag.Parse()
	options := []grpc.ServerOption{}
	if *cert != "" {
		pair, err := tls.LoadX509KeyPair(*cert, *key)
		if err != nil {
			panic(err)
		}
		config := &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{pair}}
		if *ca != "" {
			pem, err := os.ReadFile(*ca)
			if err != nil {
				panic(err)
			}
			pool := x509.NewCertPool()
			if !pool.AppendCertsFromPEM(pem) {
				panic("invalid fixture CA")
			}
			config.ClientCAs = pool
			config.ClientAuth = tls.RequireAndVerifyClientCert
		}
		options = append(options, grpc.Creds(credentials.NewTLS(config)))
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	server := grpc.NewServer(options...)
	proto.RegisterDocReaderServer(server, &reader{})
	fmt.Println(listener.Addr().String())
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	go func() { <-signals; server.Stop() }()
	if err := server.Serve(listener); err != nil {
		panic(err)
	}
}
