package com.tencent.weknora.docreader;

import docreader.DocReaderGrpc;
import docreader.Docreader;
import io.grpc.CallCredentials;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Native Java adapter for the unchanged Python docreader protocol. */
public final class DocReaderClient implements RemoteDocumentReader {
    private record Connection(ManagedChannel channel, DocReaderGrpc.DocReaderBlockingStub stub) { }
    private final DocReaderSettings settings;
    private volatile Connection connection;

    public DocReaderClient(DocReaderSettings settings) throws IOException {
        this.settings = settings;
        if (!settings.address().isEmpty()) reconnect(settings.address());
    }

    public synchronized void reconnect(String address) throws IOException {
        close();
        if (address == null || address.isEmpty()) return;
        var builder = NettyChannelBuilder.forTarget("dns:///" + address)
                .defaultLoadBalancingPolicy("round_robin")
                .maxInboundMessageSize(settings.maxMessageBytes());
        if (settings.tlsEnabled()) {
            var tls = GrpcSslContexts.forClient().protocols("TLSv1.3", "TLSv1.2");
            if (settings.ca() != null) tls.trustManager(settings.ca().toFile());
            if (settings.certificate() != null) tls.keyManager(settings.certificate().toFile(), settings.key().toFile());
            builder.sslContext(tls.build());
            if (!settings.serverName().isEmpty()) builder.overrideAuthority(settings.serverName());
        } else {
            builder.usePlaintext();
        }
        ManagedChannel channel = builder.build();
        var stub = DocReaderGrpc.newBlockingStub(channel)
                .withMaxInboundMessageSize(settings.maxMessageBytes())
                .withMaxOutboundMessageSize(settings.maxMessageBytes());
        if (!settings.authToken().isEmpty()) stub = stub.withCallCredentials(new BearerToken(settings.authToken(), settings.tlsEnabled()));
        connection = new Connection(channel, stub);
    }

    /** Like Go IsConnected, this means a channel is configured, not a live health probe. */
    public boolean isConnected() { return connection != null; }

    public DocumentReadResult read(DocumentReadRequest request) {
        return read(request, settings.callTimeout());
    }

    /** Current gRPC Context cancellation/deadline propagates and can shorten this budget. */
    public DocumentReadResult read(DocumentReadRequest request, Duration timeout) {
        DocReaderGrpc.DocReaderBlockingStub stub = timedStub(timeout);
        var wire = request.toProto();
        checkOutboundSize(wire.getSerializedSize());
        try {
            return readStream(stub.readStream(wire));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() != Status.Code.UNIMPLEMENTED) throw ex;
            // Same stub/deadline: a version fallback never resets the total RPC budget.
            return fromUnary(stub.read(wire));
        }
    }

    public List<DocumentReadResult.ParserEngineInfo> listEngines(Map<String, String> overrides) {
        return listEngines(overrides, settings.callTimeout());
    }

    public List<DocumentReadResult.ParserEngineInfo> listEngines(Map<String, String> overrides, Duration timeout) {
        var stub = timedStub(timeout);
        var request = Docreader.ListEnginesRequest.newBuilder();
        if (overrides != null) request.putAllConfigOverrides(overrides);
        var wire = request.build();
        checkOutboundSize(wire.getSerializedSize());
        return stub.listEngines(wire).getEnginesList().stream()
                .map(engine -> new DocumentReadResult.ParserEngineInfo(engine.getName(), engine.getDescription(),
                        engine.getFileTypesList(), engine.getAvailable(), engine.getUnavailableReason(), engine.getFileTypesCount() == 0)).toList();
    }

    private DocReaderGrpc.DocReaderBlockingStub timedStub(Duration timeout) {
        Connection current = connection;
        if (current == null) throw Status.UNAVAILABLE.withDescription("docreader service not connected").asRuntimeException();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("RPC timeout must be positive");
        Duration effective = timeout.compareTo(settings.callTimeout()) < 0 ? timeout : settings.callTimeout();
        return current.stub().withDeadline(Deadline.after(effective.toNanos(), TimeUnit.NANOSECONDS));
    }

    private void checkOutboundSize(int bytes) {
        if (bytes > settings.maxMessageBytes()) throw Status.RESOURCE_EXHAUSTED
                .withDescription("docreader request exceeds configured gRPC message size").asRuntimeException();
    }

    private DocumentReadResult readStream(Iterator<Docreader.ReadStreamResponse> frames) {
        Docreader.ReadStreamMeta meta = null;
        List<DocumentReadResult.ImageRef> images = new ArrayList<>();
        while (frames.hasNext()) {
            var frame = frames.next();
            if (frame.hasMeta()) {
                meta = frame.getMeta();
                if (meta.getImageCount() != 0) images = new ArrayList<>();
            } else if (frame.hasImage()) {
                images.add(fromImage(frame.getImage()));
            }
        }
        if (meta == null) throw new IllegalStateException("gRPC ReadStream returned no metadata frame");
        return new DocumentReadResult(meta.getMarkdownContent(), images, meta.getImageDirPath(),
                meta.getMetadataMap(), meta.getError(), SourceBlockDecoder.decode(meta.getSourceBlocksList()));
    }

    private DocumentReadResult fromUnary(Docreader.ReadResponse response) {
        return new DocumentReadResult(response.getMarkdownContent(), response.getImageRefsList().stream()
                .map(DocReaderClient::fromImage).toList(), response.getImageDirPath(), response.getMetadataMap(),
                response.getError(), SourceBlockDecoder.decode(response.getSourceBlocksList()));
    }

    private static DocumentReadResult.ImageRef fromImage(Docreader.ImageRef image) {
        return new DocumentReadResult.ImageRef(image.getFilename(), image.getOriginalRef(), image.getMimeType(),
                image.getStorageKey(), image.getImageData().toByteArray());
    }

    @Override public synchronized void close() {
        Connection old = connection;
        connection = null;
        if (old != null) old.channel().shutdownNow();
    }

    static final class BearerToken extends CallCredentials {
        private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final String token;
        private final boolean requireTls;
        BearerToken(String token, boolean requireTls) { this.token = token; this.requireTls = requireTls; }
        @Override public void applyRequestMetadata(RequestInfo requestInfo, Executor executor, MetadataApplier applier) {
            if (requireTls && requestInfo.getSecurityLevel() != SecurityLevel.PRIVACY_AND_INTEGRITY) {
                applier.fail(Status.UNAUTHENTICATED.withDescription("docreader bearer token requires TLS"));
                return;
            }
            Metadata metadata = new Metadata();
            metadata.put(AUTHORIZATION, "Bearer " + token);
            applier.apply(metadata);
        }
    }
}
