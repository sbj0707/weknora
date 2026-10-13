package com.tencent.weknora.docreader;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import docreader.DocReaderGrpc;
import docreader.Docreader;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DocReaderClientTest {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private static final DocumentReadRequest REQUEST = new DocumentReadRequest(new byte[]{0, 1, (byte)255}, "文档.pdf", "pdf", "", "测试", "builtin", "request-42", Map.of("ocr", "true"));
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @AfterEach void closeResources() throws Exception {
        for (int i = resources.size() - 1; i >= 0; i--) resources.get(i).close();
    }

    @Test void streamingMapsFileConfigImagesAndUnicodeSourceRanges() throws Exception {
        AtomicReference<Docreader.ReadRequest> received = new AtomicReference<>();
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                received.set(request);
                observer.onNext(meta("😀中\n正文", "", 2));
                observer.onNext(image("one.png", new byte[]{0, 1, 2}));
                observer.onNext(image("two.jpg", new byte[]{(byte)255}));
                observer.onCompleted();
            }
        });
        var result = client.read(REQUEST);
        assertEquals(REQUEST.toProto(), received.get());
        assertEquals("😀中\n正文", result.markdownContent());
        assertEquals(Map.of("parser", "builtin"), result.metadata());
        assertEquals("/tmp/images", result.imageDirPath());
        assertEquals(2, result.imageRefs().size());
        assertArrayEquals(new byte[]{0, 1, 2}, result.imageRefs().getFirst().imageData());
        assertEquals("original://one.png", result.imageRefs().getFirst().originalRef());
        assertEquals("storage/one.png", result.imageRefs().getFirst().storageKey());
        assertEquals(2, result.sourceBlocks().getFirst().end()); // Two code points, three UTF-16 chars.
        assertEquals(3L, result.sourceBlocks().getFirst().locator().get("page"));
    }

    @Test void fallbackOnlyForUnimplementedAndKeepsUnaryFields() throws Exception {
        AtomicInteger unary = new AtomicInteger();
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void read(Docreader.ReadRequest request, StreamObserver<Docreader.ReadResponse> observer) {
                unary.incrementAndGet();
                observer.onNext(Docreader.ReadResponse.newBuilder().setMarkdownContent("legacy")
                        .putMetadata("parser", "legacy").setImageDirPath("old-dir")
                        .addImageRefs(image("old.png", new byte[]{7}).getImage())
                        .addSourceBlocks(block(0, 2, "{\"type\":\"pdf\",\"page\":1}")).build());
                observer.onCompleted();
            }
        });
        var result = client.read(REQUEST);
        assertEquals(1, unary.get());
        assertEquals("legacy", result.markdownContent());
        assertEquals("old-dir", result.imageDirPath());
        assertArrayEquals(new byte[]{7}, result.imageRefs().getFirst().imageData());
        assertEquals(1L, result.sourceBlocks().getFirst().locator().get("page"));
    }

    @Test void transportFailureRetainsStatusAndNeverFallsBack() throws Exception {
        AtomicInteger unary = new AtomicInteger();
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onError(Status.PERMISSION_DENIED.withDescription("bad credential").asRuntimeException());
            }
            @Override public void read(Docreader.ReadRequest request, StreamObserver<Docreader.ReadResponse> observer) { unary.incrementAndGet(); }
        });
        var error = assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals(0, unary.get());
    }

    @Test void parserErrorIsReturnedAsData() throws Exception {
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(meta("", "OCR failed", 0)); observer.onCompleted();
            }
        });
        assertEquals("OCR failed", client.read(REQUEST).error());
    }

    @Test void streamWithoutMetadataFailsInsteadOfReturningPartialImages() throws Exception {
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(image("orphan.png", new byte[]{1})); observer.onCompleted();
            }
        });
        assertTrue(assertThrows(IllegalStateException.class, () -> client.read(REQUEST)).getMessage().contains("no metadata"));
    }

    @Test void sourceLocatorsApplyGoTypedJsonRulesAndUnsignedOffsets() {
        var blocks = SourceBlockDecoder.decode(List.of(
                block(0, 2, "{\"type\":\"pdf\",\"page\":3,\"unknown\":true,\"quote\":\"😀中\"}"),
                block(1, 1, "{\"type\":\"pdf\"}"),
                block(0, 2, "{\"page\":3}"),
                block(0, 2, "{\"type\":\"pdf\",\"page\":\"3\"}"),
                block(0, 2, "{\"type\":\"pdf\",\"page\":1.5}"),
                block(0, 2, "{\"type\":\"pdf\",\"partial\":\"true\"}"),
                block(0, 2, "{\"type\":\"pdf\",\"bbox\":[\"x\"]}"),
                block(0, 2, "not JSON"),
                block(-2, -1, "{\"type\":\"text\",\"start\":0,\"end\":2,\"page\":null}")));
        assertEquals(2, blocks.size());
        assertEquals(Map.of("type", "pdf", "page", 3L, "quote", "😀中"), blocks.getFirst().locator());
        assertEquals(4294967294L, blocks.getLast().start());
        assertEquals(4294967295L, blocks.getLast().end());
    }

    @Test void listEnginesPreservesAvailabilityAndOverrideMap() throws Exception {
        AtomicReference<Docreader.ListEnginesRequest> received = new AtomicReference<>();
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void listEngines(Docreader.ListEnginesRequest request, StreamObserver<Docreader.ListEnginesResponse> observer) {
                received.set(request);
                observer.onNext(Docreader.ListEnginesResponse.newBuilder()
                        .addEngines(Docreader.ParserEngineInfo.newBuilder().setName("builtin").setDescription("local").setAvailable(true).addFileTypes("pdf"))
                        .addEngines(Docreader.ParserEngineInfo.newBuilder().setName("ocr").setUnavailableReason("not configured")).build());
                observer.onCompleted();
            }
        });
        var engines = client.listEngines(Map.of("ocr_url", "http://ocr:8080"));
        assertEquals(Map.of("ocr_url", "http://ocr:8080"), received.get().getConfigOverridesMap());
        assertTrue(engines.getFirst().available());
        assertEquals(List.of("pdf"), engines.getFirst().fileTypes());
        assertFalse(engines.getFirst().fileTypesNil());
        assertTrue(engines.getLast().fileTypesNil());
        assertFalse(engines.getLast().available());
        assertEquals("not configured", engines.getLast().unavailableReason());
    }

    @Test void explicitDeadlineCancelsUnresponsiveReader() throws Exception {
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) { }
        });
        var error = assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST, Duration.ofMillis(250)));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, error.getStatus().getCode());
    }

    @Test void fallbackConsumesRemainingDeadlineRatherThanStartingAnotherBudget() throws Exception {
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        resources.add(() -> scheduler.shutdownNow());
        AtomicInteger unaryCalls = new AtomicInteger();
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void listEngines(Docreader.ListEnginesRequest request, StreamObserver<Docreader.ListEnginesResponse> observer) {
                observer.onNext(Docreader.ListEnginesResponse.getDefaultInstance()); observer.onCompleted();
            }
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                scheduler.schedule(() -> observer.onError(Status.UNIMPLEMENTED.asRuntimeException()), 180, TimeUnit.MILLISECONDS);
            }
            @Override public void read(Docreader.ReadRequest request, StreamObserver<Docreader.ReadResponse> observer) {
                unaryCalls.incrementAndGet();
                scheduler.schedule(() -> { observer.onNext(Docreader.ReadResponse.getDefaultInstance()); observer.onCompleted(); }, 300, TimeUnit.MILLISECONDS);
            }
        });
        client.listEngines(Map.of()); // Establish connection before testing the shared RPC budget.
        var error = assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST, Duration.ofMillis(400)));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, error.getStatus().getCode());
        assertEquals(1, unaryCalls.get());
    }

    @Test void oversizedInboundFrameFailsWithResourceExhausted() throws Exception {
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(meta("ok", "", 1));
                observer.onNext(image("oversized.png", new byte[1100]));
                observer.onCompleted();
            }
        }, 1024, "");
        var error = assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, error.getStatus().getCode());
    }

    @Test void parentCancellationReachesActiveServerCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        DocReaderClient client = client(new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                Context.current().addListener(context -> cancelled.countDown(), Runnable::run);
                started.countDown();
            }
        });
        try (var context = Context.current().withCancellation()) {
            var result = CompletableFuture.supplyAsync(() -> {
                try { return context.call(() -> client.read(REQUEST)); }
                catch (RuntimeException ex) { throw ex; }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            context.cancel(null);
            var error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertEquals(Status.Code.CANCELLED, Status.fromThrowable(error.getCause()).getCode());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void individualMessageCapDoesNotCapTotalStreamSize() throws Exception {
        var service = new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(meta("large scan", "", 3));
                for (int i = 0; i < 3; i++) observer.onNext(image(i + ".png", new byte[600]));
                observer.onCompleted();
            }
        };
        var client = client(service, 1024, "");
        assertEquals(1800, client.read(REQUEST).imageRefs().stream().mapToInt(image -> image.imageData().length).sum());
        var tooLarge = new DocumentReadRequest(new byte[1100], "large.pdf", "pdf", "", "", "", "", Map.of());
        var error = assertThrows(StatusRuntimeException.class, () -> client.read(tooLarge));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, error.getStatus().getCode());
    }

    @Test void bearerMetadataMatchesExistingGoPlaintextOptIn() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        var service = new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(meta("ok", "", 0)); observer.onCompleted();
            }
        };
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(service, new io.grpc.ServerInterceptor() {
                    @Override public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(io.grpc.ServerCall<ReqT, RespT> call, Metadata headers, io.grpc.ServerCallHandler<ReqT, RespT> next) {
                        authorization.set(headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
                        return next.startCall(call, headers);
                    }
                })).build().start();
        resources.add(() -> { server.shutdownNow(); server.awaitTermination(5, TimeUnit.SECONDS); });
        var client = new DocReaderClient(settings(server.getPort(), 1024, "test-only-token")); resources.add(client);
        assertEquals("ok", client.read(REQUEST).markdownContent());
        assertEquals("Bearer test-only-token", authorization.get());
    }

    @Test void closeAndReconnectChangeConfiguredChannelWithoutStaleClient() throws Exception {
        var service = new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(meta("alive", "", 0)); observer.onCompleted();
            }
        };
        Server server = server(service);
        var client = new DocReaderClient(settings(server.getPort(), 1024, "")); resources.add(client);
        assertTrue(client.isConnected()); client.close(); assertFalse(client.isConnected());
        assertEquals(Status.Code.UNAVAILABLE, assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST)).getStatus().getCode());
        client.reconnect("127.0.0.1:" + server.getPort());
        assertEquals("alive", client.read(REQUEST).markdownContent());
    }

    private DocReaderClient client(DocReaderGrpc.DocReaderImplBase service) throws Exception { return client(service, 50 * 1024 * 1024, ""); }
    private DocReaderClient client(DocReaderGrpc.DocReaderImplBase service, int maxBytes, String token) throws Exception {
        var client = new DocReaderClient(settings(server(service).getPort(), maxBytes, token)); resources.add(client); return client;
    }
    private Server server(DocReaderGrpc.DocReaderImplBase service) throws Exception {
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(service).build().start();
        resources.add(() -> { server.shutdownNow(); server.awaitTermination(5, TimeUnit.SECONDS); }); return server;
    }
    private static DocReaderSettings settings(int port, int maxBytes, String token) {
        return new DocReaderSettings("127.0.0.1:" + port, false, null, null, null, "", token, maxBytes, TIMEOUT);
    }
    static Docreader.ReadStreamResponse meta(String markdown, String error, int count) {
        return Docreader.ReadStreamResponse.newBuilder().setMeta(Docreader.ReadStreamMeta.newBuilder()
                .setMarkdownContent(markdown).setImageDirPath("/tmp/images").putMetadata("parser", "builtin")
                .setError(error).setImageCount(count).addSourceBlocks(block(0, 2, "{\"type\":\"pdf\",\"page\":3}"))).build();
    }
    private static Docreader.ReadStreamResponse image(String name, byte[] data) {
        return Docreader.ReadStreamResponse.newBuilder().setImage(Docreader.ImageRef.newBuilder().setFilename(name)
                .setOriginalRef("original://" + name).setMimeType("image/png").setStorageKey("storage/" + name)
                .setImageData(ByteString.copyFrom(data))).build();
    }
    static Docreader.SourceBlock block(int start, int end, String json) {
        return Docreader.SourceBlock.newBuilder().setStart(start).setEnd(end).setLocatorJson(json).build();
    }
}
