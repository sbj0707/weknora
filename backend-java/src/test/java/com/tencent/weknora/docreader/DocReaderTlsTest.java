package com.tencent.weknora.docreader;

import static org.junit.jupiter.api.Assertions.*;

import docreader.DocReaderGrpc;
import docreader.Docreader;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocReaderTlsTest {
    private static final Path CERTS = Path.of("src/test/resources/docreader-tls");
    private static final DocumentReadRequest REQUEST = new DocumentReadRequest(null, "", "", "", "", "", "", Map.of());

    @Test void customCaAndServerNamePermitVerifiedTlsConnection() throws Exception {
        try (var server = server(false)) {
            try (var client = new DocReaderClient(settings(server.port(), "docreader.test", true, false))) {
                assertEquals("TLS document", client.read(REQUEST).markdownContent());
            }
        }
    }

    @Test void certificateHostnameMismatchRejectsConnection() throws Exception {
        try (var server = server(false); var client = new DocReaderClient(settings(server.port(), "wrong.test", true, false))) {
            var error = assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST));
            assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        }
    }

    @Test void untrustedServerCertificateIsRejected() throws Exception {
        try (var server = server(false); var client = new DocReaderClient(settings(server.port(), "docreader.test", false, false))) {
            var error = assertThrows(StatusRuntimeException.class, () -> client.read(REQUEST));
            assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        }
    }

    @Test void mtlsRequiresClientCertificateAndAcceptsTrustedClientPair() throws Exception {
        try (var server = server(true)) {
            try (var missing = new DocReaderClient(settings(server.port(), "docreader.test", true, false))) {
                assertThrows(StatusRuntimeException.class, () -> missing.read(REQUEST));
            }
            try (var client = new DocReaderClient(settings(server.port(), "docreader.test", true, true))) {
                assertEquals("TLS document", client.read(REQUEST).markdownContent());
            }
        }
    }

    @Test void tlsRequiredTokenIsNeverSentOverPlaintext() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service = new DocReaderGrpc.DocReaderImplBase() {
            @Override public void listEngines(Docreader.ListEnginesRequest request, StreamObserver<Docreader.ListEnginesResponse> observer) {
                calls.incrementAndGet(); observer.onNext(Docreader.ListEnginesResponse.getDefaultInstance()); observer.onCompleted();
            }
        };
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(service).build().start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            var stub = DocReaderGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS)
                    .withCallCredentials(new DocReaderClient.BearerToken("test-only-token", true));
            var error = assertThrows(StatusRuntimeException.class, () -> stub.listEngines(Docreader.ListEnginesRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
            assertEquals(0, calls.get());
        } finally {
            channel.shutdownNow(); channel.awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow(); server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private record ServerHandle(io.grpc.Server server) implements AutoCloseable {
        int port() { return server.getPort(); }
        @Override public void close() throws Exception { server.shutdownNow(); server.awaitTermination(5, TimeUnit.SECONDS); }
    }
    private ServerHandle server(boolean mtls) throws Exception {
        var tls = GrpcSslContexts.forServer(CERTS.resolve("server.pem").toFile(), CERTS.resolve("server.key").toFile())
                .protocols("TLSv1.3", "TLSv1.2");
        if (mtls) tls.trustManager(CERTS.resolve("ca.pem").toFile()).clientAuth(ClientAuth.REQUIRE);
        var service = new DocReaderGrpc.DocReaderImplBase() {
            @Override public void readStream(Docreader.ReadRequest request, StreamObserver<Docreader.ReadStreamResponse> observer) {
                observer.onNext(DocReaderClientTest.meta("TLS document", "", 0)); observer.onCompleted();
            }
        };
        return new ServerHandle(NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(tls.build()).addService(service).build().start());
    }
    private DocReaderSettings settings(int port, String serverName, boolean ca, boolean mtls) {
        return new DocReaderSettings("127.0.0.1:" + port, true, mtls ? CERTS.resolve("client.pem") : null,
                mtls ? CERTS.resolve("client.key") : null, ca ? CERTS.resolve("ca.pem") : null,
                serverName, "", 50 * 1024 * 1024, Duration.ofSeconds(4));
    }
}
