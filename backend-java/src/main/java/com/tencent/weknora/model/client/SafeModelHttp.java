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
import java.util.concurrent.atomic.*;

/** HTTP/1.1 transport using existing Netty: checked DNS is pinned, HTTPS retains host/SNI verification. */
public final class SafeModelHttp implements AutoCloseable {
    public static final ObjectMapper JSON=new ObjectMapper();
    @FunctionalInterface public interface AddressResolver {InetAddress[] resolve(URI target,CallContext context);}
    private final AddressResolver resolver;private final int maxRedirects;private final boolean allowCrossOriginGet;private final NioEventLoopGroup eventLoop;private final SslContext tls;
    private final ExecutorService dns=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"model-deadlines");t.setDaemon(true);return t;});
    private final Map<PoolKey,ArrayDeque<PersistentConnection>> idle=new HashMap<>();
    private final Set<PersistentConnection> persistent=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public SafeModelHttp(AddressResolver resolver){this(resolver,10,false);}
    /** Parser probes use Go SSRF-safe GET redirect semantics and their own redirect count. */
    public SafeModelHttp(AddressResolver resolver,int maxRedirects,boolean allowCrossOriginGet){if(maxRedirects<1)throw new IllegalArgumentException("Redirect limit must be positive");this.maxRedirects=maxRedirects;this.allowCrossOriginGet=allowCrossOriginGet;this.resolver=resolver;eventLoop=new NioEventLoopGroup(2,r->{Thread t=new Thread(r,"model-io");t.setDaemon(true);return t;});try{tls=SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build();}catch(Exception ex){throw new IllegalStateException("Model TLS initialization failed");}}
    public Response post(CallContext context,String target,Map<String,String> headers,byte[] body,Duration requestTimeout){return request(context,target,"POST",headers,body,requestTimeout);}
    public Response get(CallContext context,String target,Map<String,String> headers,Duration timeout){return request(context,target,"GET",headers,new byte[0],timeout);}
    public Response head(CallContext context,String target,Map<String,String> headers,Duration timeout){return request(context,target,"HEAD",headers,new byte[0],timeout);}
    /** Physical retrieval adapters share DNS pinning and cancellation for their HTTP verbs. */
    public Response exchange(CallContext context,String target,String method,Map<String,String> headers,byte[] body,Duration timeout){if(!Set.of("GET","HEAD","POST","PUT","DELETE").contains(method))throw new IllegalArgumentException("Unsupported HTTP method");return request(context,target,method,headers,body,timeout);}
    /** Elasticsearch's transport uses RoundTrip directly, so redirect responses remain responses. */
    public Response exchangeWithoutRedirects(CallContext context,String target,String method,Map<String,String> headers,byte[] body,Duration timeout){if(!Set.of("GET","HEAD","POST","PUT","DELETE").contains(method))throw new IllegalArgumentException("Unsupported HTTP method");context.check();Instant limit=context.deadline();if(timeout!=null&&!timeout.isZero()&&!timeout.isNegative()){Instant bound=Instant.now().plus(timeout);if(limit==null||bound.isBefore(limit))limit=bound;}return send(context,parse(target),method,headers,body,limit);}
    /** DNS-pinned persistent HTTP/1.1 exchanges for Go transports which reuse drained connections. */
    public Response exchangeReusableWithoutRedirects(CallContext context,String target,String method,Map<String,String> headers,byte[] body,Duration timeout){
        if(!Set.of("GET","HEAD","POST","PUT","DELETE").contains(method))throw new IllegalArgumentException("Unsupported HTTP method");
        context.check();Instant limit=context.deadline();if(timeout!=null&&!timeout.isZero()&&!timeout.isNegative()){Instant bound=Instant.now().plus(timeout);if(limit==null||bound.isBefore(limit))limit=bound;}
        URI uri=parse(target);InetAddress[] addresses=resolve(context,uri,limit);HttpExchangeException last=null;
        for(InetAddress address:addresses){context.check();PoolKey key=new PoolKey(uri.getScheme(),uri.getHost(),port(uri),address);
            PersistentConnection connection=borrow(key);try{return connection.exchange(context,uri,method,headers,body,limit);}catch(ConnectFailure failure){connection.close();last=new HttpExchangeException("Model connection failed","dial tcp "+(address.getHostAddress().contains(":")?"["+address.getHostAddress()+"]":address.getHostAddress())+":"+port(uri)+": connect: connection refused");}}
        throw last==null?new ModelClientException("Model connection failed",true):last;
    }
    /** Infrastructure health probes inspect the original HTTP status and never follow redirects. */
    public Response getWithoutRedirects(CallContext context,String target,Map<String,String> headers,Duration timeout){context.check();Instant limit=context.deadline();if(timeout!=null&&!timeout.isZero()&&!timeout.isNegative()){Instant bound=Instant.now().plus(timeout);if(limit==null||bound.isBefore(limit))limit=bound;}return send(context,parse(target),"GET",headers,new byte[0],limit);}
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
        InetAddress[] addresses=resolve(context,target,limit);
        ModelClientException last=null;
        for(InetAddress address:addresses){context.check();try{return connect(context,target,method,headers,body,address,limit);}catch(ConnectFailure ex){last=new ModelClientException("Model connection failed",true);}}
        throw last==null?new ModelClientException("Model connection failed",true):last;
    }
    private InetAddress[] resolve(CallContext context,URI target,Instant limit){
        InetAddress[] addresses;Future<InetAddress[]> lookup=dns.submit(()->resolver.resolve(target,context));try(var cancel=context.onCancel(()->lookup.cancel(true))){addresses=await(lookup,context,limit);}catch(ModelClientException|CancellationException ex){throw ex;}catch(Exception ex){throw new ModelClientException("Model DNS validation failed");}
        if(addresses==null||addresses.length==0)throw new ModelClientException("Model DNS returned no addresses",true);return addresses;
    }
    private Response connect(CallContext context,URI target,String method,Map<String,String> headers,byte[] body,InetAddress address,Instant limit){
        Inbox inbox=new Inbox();AtomicReference<Channel> channel=new AtomicReference<>();
        AutoCloseable cancel=context.onCancel(()->{inbox.fail(new CancellationException("Model call cancelled"));Channel c=channel.get();if(c!=null)c.close();});
        ScheduledFuture<?> timeout=limit==null?null:deadlines.schedule(()->{inbox.fail(new ModelClientException("Model call deadline exceeded",true));Channel c=channel.get();if(c!=null)c.close();},Math.max(0,Duration.between(Instant.now(),limit).toMillis()),TimeUnit.MILLISECONDS);
        try{
            Bootstrap bootstrap=new Bootstrap().group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,30000).option(ChannelOption.AUTO_READ,false).option(ChannelOption.RCVBUF_ALLOCATOR,new FixedRecvByteBufAllocator(8192).maxMessagesPerRead(1)).handler(new ChannelInitializer<Channel>(){
                @Override protected void initChannel(Channel ch){channel.set(ch);inbox.channel=ch;
                    if(target.getScheme().equals("https")){SslHandler handler=tls.newHandler(ch.alloc(),target.getHost(),port(target));var parameters=handler.engine().getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");handler.engine().setSSLParameters(parameters);handler.setHandshakeTimeoutMillis(10000);ch.pipeline().addLast(handler);}
                    ch.pipeline().addLast(new HttpClientCodec(4096,32768,8192));ch.pipeline().addLast(new HttpContentDecompressor());ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpObject>(){
                        @Override public void channelActive(ChannelHandlerContext ctx){String path=target.getRawPath();if(path==null||path.isEmpty())path="/";if(target.getRawQuery()!=null)path+="?"+target.getRawQuery();var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.valueOf(method),path,Unpooled.wrappedBuffer(body));
                            request.headers().set(HttpHeaderNames.HOST,target.getRawAuthority()).set(HttpHeaderNames.CONTENT_LENGTH,body.length).set(HttpHeaderNames.CONNECTION,"close").set(HttpHeaderNames.ACCEPT_ENCODING,"gzip");
                            try{headers.forEach((key,value)->request.headers().set(key,new String(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.ISO_8859_1)));ctx.writeAndFlush(request).addListener(f->{if(!f.isSuccess())inbox.fail(new ModelClientException("Model request send failed",true));});inbox.requestRead();}catch(Exception ex){ReferenceCountUtil.release(request);inbox.fail(new ModelClientException("Invalid model request headers"));ctx.close();}}
                        @Override protected void channelRead0(ChannelHandlerContext ctx,HttpObject object){if(!object.decoderResult().isSuccess()){inbox.fail(new ModelClientException("Invalid model HTTP response",true));ctx.close();return;}
                            if(object instanceof HttpResponse response){if(response.status().code()<200){if(response.status().code()==101){inbox.fail(new ModelClientException("Model protocol upgrades are not supported"));ctx.close();}return;}var responseHeaders=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);response.headers().forEach(e->responseHeaders.put(e.getKey(),e.getValue()));inbox.header.complete(new Head(response.status().code(),Collections.unmodifiableMap(responseHeaders)));}
                            if(object instanceof HttpContent content&&inbox.header.isDone()){byte[] bytes=new byte[content.content().readableBytes()];content.content().readBytes(bytes);if(bytes.length>0)inbox.offer(bytes);if(object instanceof LastHttpContent){inbox.ended=true;inbox.offer(Inbox.END);}}
                        }
                        @Override public void channelReadComplete(ChannelHandlerContext ctx){inbox.readPending=false;inbox.requestRead();}
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
    /** A typed wire failure keeps a safe default message and the corresponding Go I/O diagnostic. */
    public static final class HttpExchangeException extends ModelClientException{
        private final String goMessage;
        HttpExchangeException(String message,String goMessage){super(message,true);this.goMessage=goMessage;}
        public String goMessage(){return goMessage;}
    }
    private record PoolKey(String scheme,String host,int port,InetAddress address){}
    private PersistentConnection borrow(PoolKey key){
        synchronized(idle){if(closed)throw new ModelClientException("Model HTTP client is closed");var entries=idle.get(key);while(entries!=null&&!entries.isEmpty()){var connection=entries.removeLast();if(connection.channel!=null&&connection.channel.isActive())return connection;connection.close();}if(entries!=null)idle.remove(key);}
        var connection=new PersistentConnection(key);persistent.add(connection);return connection;
    }
    private void recycle(PersistentConnection connection){
        synchronized(idle){if(closed||!connection.channel.isActive()){connection.close();return;}var entries=idle.computeIfAbsent(connection.key,k->new ArrayDeque<>());if(entries.size()>=2){connection.close();return;}connection.lease=null;entries.addLast(connection);connection.channel.read();}
    }
    private final class PersistentConnection {
        final PoolKey key;volatile Channel channel;volatile PersistentLease lease;
        PersistentConnection(PoolKey key){this.key=key;}
        Response exchange(CallContext context,URI target,String method,Map<String,String> headers,byte[] body,Instant limit){
            var active=new PersistentLease(this,context,target,method,headers,body,limit);lease=active;
            try{
                if(channel==null){
                    Bootstrap bootstrap=new Bootstrap().group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,30000).option(ChannelOption.AUTO_READ,false).option(ChannelOption.RCVBUF_ALLOCATOR,new FixedRecvByteBufAllocator(8192).maxMessagesPerRead(1)).handler(new ChannelInitializer<Channel>(){
                        @Override protected void initChannel(Channel ch){channel=ch;active.inbox.channel=ch;
                            if(key.scheme().equals("https")){SslHandler ssl=tls.newHandler(ch.alloc(),key.host(),key.port());var parameters=ssl.engine().getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");ssl.engine().setSSLParameters(parameters);ssl.setHandshakeTimeoutMillis(10000);ch.pipeline().addLast(ssl);}
                            ch.pipeline().addLast(new HttpClientCodec(4096,32768,8192));ch.pipeline().addLast(new HttpContentDecompressor());ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpObject>(){
                                @Override public void channelActive(ChannelHandlerContext ctx){write(active);}
                                @Override protected void channelRead0(ChannelHandlerContext ctx,HttpObject object){
                                    PersistentLease current=lease;if(current==null){ctx.close();return;}Inbox inbox=current.inbox;
                                    if(!object.decoderResult().isSuccess()){current.fail(new HttpExchangeException("Invalid model HTTP response",current.chunked?"invalid byte in chunk length":"malformed HTTP response"));return;}
                                    if(object instanceof HttpResponse response){
                                        if(response.status().code()<200){if(response.status().code()==101)current.fail(new HttpExchangeException("Model protocol upgrades are not supported","net/http: HTTP/1.x transport connection broken: unexpected protocol switch"));return;}
                                        current.keepAlive=HttpUtil.isKeepAlive(response);current.chunked=HttpUtil.isTransferEncodingChunked(response);
                                        var responseHeaders=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);response.headers().forEach(e->responseHeaders.put(e.getKey(),e.getValue()));inbox.header.complete(new Head(response.status().code(),Collections.unmodifiableMap(responseHeaders)));
                                    }
                                    if(object instanceof HttpContent content&&inbox.header.isDone()){byte[] bytes=new byte[content.content().readableBytes()];content.content().readBytes(bytes);if(bytes.length>0)inbox.offer(bytes);if(object instanceof LastHttpContent){inbox.ended=true;inbox.offer(Inbox.END);}}
                                }
                                @Override public void channelReadComplete(ChannelHandlerContext ctx){var current=lease;if(current==null){ctx.read();return;}current.inbox.readPending=false;current.inbox.requestRead();}
                                @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable error){var current=lease;if(current!=null)current.fail(new HttpExchangeException("Model transport failed",current.inbox.header.isDone()?"unexpected EOF":"EOF"));else ctx.close();}
                                @Override public void channelInactive(ChannelHandlerContext ctx){persistent.remove(PersistentConnection.this);var current=lease;if(current!=null&&!current.inbox.ended)current.fail(new HttpExchangeException("Model response was truncated",current.inbox.header.isDone()?"unexpected EOF":"EOF"));}
                            });
                        }
                    });
                    ChannelFuture opened=bootstrap.connect(new InetSocketAddress(key.address(),key.port()));try{await(opened,context,limit);}catch(ModelClientException failed){if(active.inbox.header.isDone())throw failed;throw new ConnectFailure();}
                }else{active.inbox.channel=channel;channel.eventLoop().execute(()->write(active));}
                Head head=await(active.inbox.header,context,limit);return new Response(head.status,head.headers,active.inbox,null,null,active::release);
            }catch(RuntimeException error){active.abort();throw error;}
        }
        void write(PersistentLease current){
            if(!current.live.get()){close();return;}String path=current.target.getRawPath();if(path==null||path.isEmpty())path="/";if(current.target.getRawQuery()!=null)path+="?"+current.target.getRawQuery();var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.valueOf(current.method),path,Unpooled.wrappedBuffer(current.body));
            request.headers().set(HttpHeaderNames.HOST,current.target.getRawAuthority()).set(HttpHeaderNames.CONTENT_LENGTH,current.body.length).set(HttpHeaderNames.ACCEPT_ENCODING,"gzip");
            try{current.headers.forEach((key,value)->request.headers().set(key,new String(value.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1)));channel.writeAndFlush(request).addListener(f->{if(!f.isSuccess())current.fail(new HttpExchangeException("Model request send failed","EOF"));});current.inbox.requestRead();}catch(Exception invalid){ReferenceCountUtil.release(request);current.fail(new ModelClientException("Invalid model request headers"));}
        }
        void close(){Channel current=channel;if(current!=null)current.close();persistent.remove(this);}
    }
    private final class PersistentLease {
        final PersistentConnection connection;final Inbox inbox=new Inbox(true);final AtomicBoolean live=new AtomicBoolean(true);
        final URI target;final String method;final Map<String,String> headers;final byte[] body;final AutoCloseable cancel;final ScheduledFuture<?> timeout;
        volatile boolean keepAlive,chunked;
        PersistentLease(PersistentConnection connection,CallContext context,URI target,String method,Map<String,String> headers,byte[] body,Instant limit){
            this.connection=connection;this.target=target;this.method=method;this.headers=Map.copyOf(headers);this.body=body;
            cancel=context.onCancel(()->fail(new CancellationException("Model call cancelled")));
            timeout=limit==null?null:deadlines.schedule(()->fail(new ModelClientException("Model call deadline exceeded",true)),Math.max(0,Duration.between(Instant.now(),limit).toMillis()),TimeUnit.MILLISECONDS);
        }
        void fail(RuntimeException error){if(live.compareAndSet(true,false)){inbox.fail(error);connection.close();}}
        void cleanup(){SafeModelHttp.close(cancel);if(timeout!=null)timeout.cancel(false);}
        void abort(){if(live.compareAndSet(true,false))inbox.close();cleanup();connection.close();}
        void release(){boolean owner=live.compareAndSet(true,false);cleanup();if(!owner)return;
            Runnable finish=()->{if(keepAlive&&inbox.ended&&inbox.error==null&&inbox.queuedBytes.get()==0&&(inbox.current==null||inbox.offset==inbox.current.length)){inbox.discard();recycle(connection);}else inbox.close();};
            Channel channel=connection.channel;if(channel==null||channel.eventLoop().inEventLoop()){finish.run();return;}
            try{channel.eventLoop().submit(finish).syncUninterruptibly();}catch(RejectedExecutionException stopped){connection.close();}
        }
    }
    private static final class Inbox extends InputStream{
        final boolean bufferedBeforeFailure;
        Inbox(){this(false);}Inbox(boolean bufferedBeforeFailure){this.bufferedBeforeFailure=bufferedBeforeFailure;}
        static final byte[] END=new byte[0];
        // One wire read may decode hundreds of tiny chunks or expand gzip into many body chunks.
        // Pause subsequent socket reads at the watermarks; never discard the rest of that read.
        final BlockingQueue<byte[]> queue=new LinkedBlockingQueue<>();
        final AtomicInteger queuedChunks=new AtomicInteger();final AtomicLong queuedBytes=new AtomicLong();
        final CompletableFuture<Head> header=new CompletableFuture<>();volatile Channel channel;volatile boolean ended,closed;
        volatile RuntimeException error;boolean readPending;byte[] current;int offset;
        void offer(byte[] bytes){if(closed||error!=null)return;if(bytes!=END){queuedChunks.incrementAndGet();queuedBytes.addAndGet(bytes.length);}queue.offer(bytes);}
        void requestRead(){
            Channel c=channel;if(c==null||ended||closed||error!=null)return;
            Runnable read=()->{if(!readPending&&!ended&&!closed&&error==null&&c.isActive()&&queuedChunks.get()<128&&queuedBytes.get()<1024*1024){readPending=true;c.read();}};
            try{if(c.eventLoop().inEventLoop())read.run();else c.eventLoop().execute(read);}
            catch(RejectedExecutionException stopped){if(!ended&&!closed&&error==null)fail(new ModelClientException("Model transport failed",true));}
        }
        void fail(RuntimeException ex){if(error==null)error=ex;header.completeExceptionally(ex);queue.offer(END);Channel c=channel;if(c!=null)c.close();}
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] dest,int start,int length)throws IOException{
            Objects.checkFromIndexSize(start,length,dest.length);if(length==0)return 0;
            while(current==null||offset==current.length){
                if(error!=null&&(!bufferedBeforeFailure||!(error instanceof HttpExchangeException)||queue.isEmpty()))throw error;if(closed)return -1;if(ended&&queue.isEmpty())return -1;
                requestRead();
                try{current=queue.poll(50,TimeUnit.MILLISECONDS);offset=0;if(current==END){if(error!=null)throw error;return -1;}if(current!=null){queuedChunks.decrementAndGet();queuedBytes.addAndGet(-current.length);}requestRead();}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new CancellationException("Model call cancelled");}
            }
            int n=Math.min(length,current.length-offset);System.arraycopy(current,offset,dest,start,n);offset+=n;return n;
        }
        void discard(){closed=true;queue.clear();queuedChunks.set(0);queuedBytes.set(0);queue.offer(END);}
        @Override public void close(){discard();if(channel!=null)channel.close();}
    }
    public static final class Response implements AutoCloseable{
        private final int status;private final Map<String,String> headers;private final Inbox body;private final AutoCloseable cancel;private final ScheduledFuture<?> timeout;
        private final Runnable release;
        Response(int status,Map<String,String> headers,Inbox body,AutoCloseable cancel,ScheduledFuture<?> timeout){this(status,headers,body,cancel,timeout,null);}
        Response(int status,Map<String,String> headers,Inbox body,AutoCloseable cancel,ScheduledFuture<?> timeout,Runnable release){this.status=status;this.headers=headers;this.body=body;this.cancel=cancel;this.timeout=timeout;this.release=release;}
        public int status(){return status;}public Map<String,String> headers(){return headers;}public InputStream body(){return body;}
        public byte[] bytes(int max){try{byte[] bytes=body.readNBytes(max+1);if(bytes.length>max)throw new ModelClientException("Model response exceeds maximum size");return bytes;}catch(IOException ex){throw new ModelClientException("Model response read failed",true);}}
        public String text(int max){return new String(bytes(max),StandardCharsets.UTF_8);}
        @Override public void close(){if(release!=null){release.run();return;}body.close();SafeModelHttp.close(cancel);if(timeout!=null)timeout.cancel(false);}
    }
    @Override public void close(){closed=true;synchronized(idle){idle.clear();}persistent.forEach(PersistentConnection::close);dns.shutdownNow();deadlines.shutdownNow();eventLoop.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly();}
}
