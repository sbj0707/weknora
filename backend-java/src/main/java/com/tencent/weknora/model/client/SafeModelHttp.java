package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.netty.shaded.io.netty.bootstrap.Bootstrap;
import io.grpc.netty.shaded.io.netty.buffer.Unpooled;
import io.grpc.netty.shaded.io.netty.channel.*;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.grpc.netty.shaded.io.netty.handler.codec.http.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.*;
import io.grpc.netty.shaded.io.netty.util.ReferenceCountUtil;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** HTTP/1.1 transport using existing Netty: checked DNS is pinned, HTTPS retains host/SNI verification. */
public final class SafeModelHttp implements AutoCloseable {
    public static final ObjectMapper JSON=new ObjectMapper();
    @FunctionalInterface public interface AddressResolver {InetAddress[] resolve(URI target,CallContext context);}
    private final AddressResolver resolver;private final int maxRedirects;private final boolean allowCrossOriginGet;private final NioEventLoopGroup eventLoop;private final SslContext tls;
    private final ExecutorService dns=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"model-deadlines");t.setDaemon(true);return t;});
    public SafeModelHttp(AddressResolver resolver){this(resolver,10,false);}
    /** Parser probes use Go SSRF-safe GET redirect semantics and their own redirect count. */
    public SafeModelHttp(AddressResolver resolver,int maxRedirects,boolean allowCrossOriginGet){if(maxRedirects<1)throw new IllegalArgumentException("Redirect limit must be positive");this.maxRedirects=maxRedirects;this.allowCrossOriginGet=allowCrossOriginGet;this.resolver=resolver;eventLoop=new NioEventLoopGroup(2,r->{Thread t=new Thread(r,"model-io");t.setDaemon(true);return t;});try{tls=SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build();}catch(Exception ex){throw new IllegalStateException("Model TLS initialization failed");}}
    public Response post(CallContext context,String target,Map<String,String> headers,byte[] body,Duration requestTimeout){return request(context,target,"POST",headers,body,requestTimeout);}
    public Response get(CallContext context,String target,Map<String,String> headers,Duration timeout){return request(context,target,"GET",headers,new byte[0],timeout);}
    public Response head(CallContext context,String target,Map<String,String> headers,Duration timeout){return request(context,target,"HEAD",headers,new byte[0],timeout);}
    private Response request(CallContext context,String target,String method,Map<String,String> headers,byte[] body,Duration requestTimeout){
        URI original=parse(target),current=original;byte[] payload=body;Map<String,String> originalHeaders=Map.copyOf(headers);boolean crossOriginAllowed=allowCrossOriginGet&&Set.of("GET","HEAD").contains(method)&&body.length==0;boolean includeBody=true;
        Instant limit=context.deadline();if(requestTimeout!=null&&!requestTimeout.isZero()&&!requestTimeout.isNegative()){Instant clientLimit=Instant.now().plus(requestTimeout);if(limit==null||clientLimit.isBefore(limit))limit=clientLimit;}
        for(int redirects=0;;redirects++){
            context.check();if(!sameOrigin(original,current)&&!crossOriginAllowed)throw new ModelClientException("redirect blocked: cross-origin request replay is forbidden");
            Response response=send(context,current,method,headers,payload,limit);int status=response.status();String location=response.headers().get("location");
            if(!Set.of(301,302,303,307,308).contains(status)||location==null||location.isEmpty())return response;
            response.close();if(redirects>=maxRedirects-1)throw new ModelClientException("stopped after "+maxRedirects+" redirects");
            URI next;try{next=current.resolve(location);}catch(Exception ex){throw new ModelClientException("redirect blocked: invalid target URL");}next=parse(next.toString());if(!sameOrigin(original,next)&&!crossOriginAllowed)throw new ModelClientException("redirect blocked: cross-origin request replay is forbidden");
            headers=new LinkedHashMap<>(originalHeaders);if(!sameOrigin(original,next))headers.entrySet().removeIf(e->!Set.of("accept","accept-language","user-agent").contains(e.getKey().toLowerCase(Locale.ROOT)));
            if(Set.of(301,302,303).contains(status)){
                if(!Set.of("GET","HEAD").contains(method))method="GET";
                payload=new byte[0];includeBody=false;
            }
            if(!includeBody)headers.entrySet().removeIf(e->Set.of("content-length","content-type","content-encoding","content-language","content-location").contains(e.getKey().toLowerCase(Locale.ROOT)));
            current=next;
        }
    }
    private static URI parse(String target){try{URI uri=URI.create(target);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();return uri;}catch(Exception ex){throw new ModelClientException("Model endpoint is not a valid http/https URL");}}
    private static int port(URI uri){return uri.getPort()>=0?uri.getPort():uri.getScheme().equals("https")?443:80;}
    private static boolean sameOrigin(URI a,URI b){return Objects.equals(a.getScheme(),b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);}
    private Response send(CallContext context,URI target,String method,Map<String,String> headers,byte[] body,Instant limit){
        InetAddress[] addresses;Future<InetAddress[]> lookup=dns.submit(()->resolver.resolve(target,context));try(var cancel=context.onCancel(()->lookup.cancel(true))){addresses=await(lookup,context,limit);}catch(ModelClientException|CancellationException ex){throw ex;}catch(Exception ex){throw new ModelClientException("Model DNS validation failed");}
        if(addresses==null||addresses.length==0)throw new ModelClientException("Model DNS returned no addresses",true);
        ModelClientException last=null;
        for(InetAddress address:addresses){context.check();try{return connect(context,target,method,headers,body,address,limit);}catch(ConnectFailure ex){last=new ModelClientException("Model connection failed",true);}}
        throw last==null?new ModelClientException("Model connection failed",true):last;
    }
    private Response connect(CallContext context,URI target,String method,Map<String,String> headers,byte[] body,InetAddress address,Instant limit){
        Inbox inbox=new Inbox();AtomicReference<Channel> channel=new AtomicReference<>();
        AutoCloseable cancel=context.onCancel(()->{inbox.fail(new CancellationException("Model call cancelled"));Channel c=channel.get();if(c!=null)c.close();});
        ScheduledFuture<?> timeout=limit==null?null:deadlines.schedule(()->{inbox.fail(new ModelClientException("Model call deadline exceeded",true));Channel c=channel.get();if(c!=null)c.close();},Math.max(0,Duration.between(Instant.now(),limit).toMillis()),TimeUnit.MILLISECONDS);
        try{
            Bootstrap bootstrap=new Bootstrap().group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,30000).option(ChannelOption.AUTO_READ,false).handler(new ChannelInitializer<Channel>(){
                @Override protected void initChannel(Channel ch){channel.set(ch);inbox.channel=ch;
                    if(target.getScheme().equals("https")){SslHandler handler=tls.newHandler(ch.alloc(),target.getHost(),port(target));var parameters=handler.engine().getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");handler.engine().setSSLParameters(parameters);handler.setHandshakeTimeoutMillis(10000);ch.pipeline().addLast(handler);}
                    ch.pipeline().addLast(new HttpClientCodec(4096,32768,8192));ch.pipeline().addLast(new HttpContentDecompressor());ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpObject>(){
                        @Override public void channelActive(ChannelHandlerContext ctx){String path=target.getRawPath();if(path==null||path.isEmpty())path="/";if(target.getRawQuery()!=null)path+="?"+target.getRawQuery();var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.valueOf(method),path,Unpooled.wrappedBuffer(body));
                            request.headers().set(HttpHeaderNames.HOST,target.getRawAuthority()).set(HttpHeaderNames.CONTENT_LENGTH,body.length).set(HttpHeaderNames.CONNECTION,"close").set(HttpHeaderNames.ACCEPT_ENCODING,"gzip");
                            try{headers.forEach((key,value)->request.headers().set(key,new String(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.ISO_8859_1)));ctx.writeAndFlush(request).addListener(f->{if(!f.isSuccess())inbox.fail(new ModelClientException("Model request send failed",true));});ctx.read();}catch(Exception ex){ReferenceCountUtil.release(request);inbox.fail(new ModelClientException("Invalid model request headers"));ctx.close();}}
                        @Override protected void channelRead0(ChannelHandlerContext ctx,HttpObject object){if(!object.decoderResult().isSuccess()){inbox.fail(new ModelClientException("Invalid model HTTP response",true));ctx.close();return;}
                            if(object instanceof HttpResponse response){if(response.status().code()<200){if(response.status().code()==101){inbox.fail(new ModelClientException("Model protocol upgrades are not supported"));ctx.close();}return;}var responseHeaders=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);response.headers().forEach(e->responseHeaders.put(e.getKey(),e.getValue()));inbox.header.complete(new Head(response.status().code(),Collections.unmodifiableMap(responseHeaders)));}
                            if(object instanceof HttpContent content&&inbox.header.isDone()){byte[] bytes=new byte[content.content().readableBytes()];content.content().readBytes(bytes);if(bytes.length>0)inbox.offer(bytes);if(object instanceof LastHttpContent){inbox.ended=true;inbox.offer(Inbox.END);}}
                        }
                        @Override public void channelReadComplete(ChannelHandlerContext ctx){if(!inbox.ended&&inbox.queue.remainingCapacity()>8)ctx.read();}
                        @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable error){inbox.fail(new ModelClientException("Model transport failed",true));ctx.close();}
                        @Override public void channelInactive(ChannelHandlerContext ctx){if(!inbox.ended)inbox.fail(new ModelClientException("Model response was truncated",true));}
                    });
                }
            });
            ChannelFuture connection=bootstrap.connect(new InetSocketAddress(address,port(target)));try{await(connection,context,limit);}catch(ModelClientException ex){if(inbox.header.isDone())throw ex;throw new ConnectFailure();}
            Head head=await(inbox.header,context,limit);return new Response(head.status,head.headers,inbox,cancel,timeout);
        }catch(RuntimeException ex){Channel ch=channel.get();if(ch!=null)ch.close();close(cancel);if(timeout!=null)timeout.cancel(false);throw ex;}
    }
    private static <T>T await(Future<T> future,CallContext context,Instant limit){while(true){context.check();if(limit!=null&&!Instant.now().isBefore(limit)){future.cancel(true);throw new ModelClientException("Model call deadline exceeded",true);}try{return future.get(50,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(InterruptedException ex){Thread.currentThread().interrupt();context.check();}catch(CancellationException ex){throw ex;}catch(ExecutionException ex){if(ex.getCause() instanceof RuntimeException cause)throw cause;throw new ModelClientException("Model transport failed",true);}}}
    private static void close(AutoCloseable value){try{value.close();}catch(Exception ignored){}}
    private record Head(int status,Map<String,String> headers){}
    private static final class ConnectFailure extends RuntimeException{}
    private static final class Inbox extends InputStream{
        static final byte[] END=new byte[0];final BlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(128);final CompletableFuture<Head> header=new CompletableFuture<>();volatile Channel channel;volatile boolean ended,closed;volatile RuntimeException error;byte[] current;int offset;
        void offer(byte[] bytes){if(!queue.offer(bytes))fail(new ModelClientException("Model response consumer is too slow"));}
        void fail(RuntimeException ex){if(error==null)error=ex;header.completeExceptionally(ex);queue.offer(END);Channel c=channel;if(c!=null)c.close();}
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] dest,int start,int length)throws IOException{if(length==0)return 0;while(current==null||offset==current.length){if(error!=null)throw error;if(closed)return -1;if(ended&&queue.isEmpty())return -1;try{current=queue.poll(50,TimeUnit.MILLISECONDS);offset=0;if(current==END){if(error!=null)throw error;return -1;}if(channel!=null)channel.read();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new CancellationException("Model call cancelled");}}int n=Math.min(length,current.length-offset);System.arraycopy(current,offset,dest,start,n);offset+=n;return n;}
        @Override public void close(){closed=true;queue.clear();queue.offer(END);if(channel!=null)channel.close();}
    }
    public static final class Response implements AutoCloseable{
        private final int status;private final Map<String,String> headers;private final Inbox body;private final AutoCloseable cancel;private final ScheduledFuture<?> timeout;
        Response(int status,Map<String,String> headers,Inbox body,AutoCloseable cancel,ScheduledFuture<?> timeout){this.status=status;this.headers=headers;this.body=body;this.cancel=cancel;this.timeout=timeout;}
        public int status(){return status;}public Map<String,String> headers(){return headers;}public InputStream body(){return body;}
        public byte[] bytes(int max){try{byte[] bytes=body.readNBytes(max+1);if(bytes.length>max)throw new ModelClientException("Model response exceeds maximum size");return bytes;}catch(IOException ex){throw new ModelClientException("Model response read failed",true);}}
        public String text(int max){return new String(bytes(max),StandardCharsets.UTF_8);}
        @Override public void close(){body.close();SafeModelHttp.close(cancel);if(timeout!=null)timeout.cancel(false);}
    }
    @Override public void close(){dns.shutdownNow();deadlines.shutdownNow();eventLoop.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly();}
}
