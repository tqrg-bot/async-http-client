/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request;

import static org.asynchttpclient.providers.netty.util.HttpUtil.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandlerExtensions;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.RandomAccessBody;
import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.providers.netty.Constants;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.FutureReaper;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.NettyResponseFutures;
import org.asynchttpclient.providers.netty.request.body.BodyChunkedInput;
import org.asynchttpclient.providers.netty.request.body.BodyFileRegion;
import org.asynchttpclient.providers.netty.request.body.FeedableBodyGenerator;
import org.asynchttpclient.providers.netty.request.body.FeedableBodyGenerator.FeedListener;
import org.asynchttpclient.providers.netty.request.body.NettyBody;
import org.asynchttpclient.providers.netty.request.body.NettyBodyBody;
import org.asynchttpclient.providers.netty.request.body.NettyFileBody;
import org.asynchttpclient.providers.netty.request.body.NettyInputStreamBody;
import org.asynchttpclient.providers.netty.request.body.NettyMultipartBody;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.ProxyUtils;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AtomicBoolean closed;
    private final AsyncHttpClientConfig config;
    private final Channels channels;

    public NettyRequestSender(AtomicBoolean closed, AsyncHttpClientConfig config, Channels channels) {
        this.closed = closed;
        this.config = config;
        this.channels = channels;
    }

    public boolean retry(Channel channel, NettyResponseFuture<?> future) {

        boolean success = false;

        if (!closed.get()) {
            channels.removeAll(channel);

            if (future == null) {
                Object attachment = Channels.getDefaultAttribute(channel);
                if (attachment instanceof NettyResponseFuture)
                    future = (NettyResponseFuture<?>) attachment;
            }

            if (future != null && future.canBeReplayed()) {
                future.setState(NettyResponseFuture.STATE.RECONNECTED);
                future.getAndSetStatusReceived(false);

                LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest());
                if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
                    AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
                }

                try {
                    sendNextRequest(future.getRequest(), future);
                    success = true;
                } catch (IOException iox) {
                    future.setState(NettyResponseFuture.STATE.CLOSED);
                    future.abort(iox);
                    LOGGER.error("Remotely Closed, unable to recover", iox);
                }
            } else {
                LOGGER.debug("Unable to recover future {}\n", future);
            }
        }
        return success;
    }

    public boolean applyIoExceptionFiltersAndReplayRequest(ChannelHandlerContext ctx, NettyResponseFuture<?> future, IOException e) throws IOException {

        boolean replayed = false;

        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(e).build();
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                channels.abort(future, efe);
            }
        }

        if (fc.replayRequest()) {
            replayRequest(future, fc, ctx);
            replayed = true;
        }
        return replayed;
    }

    public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> f) throws IOException {
        sendRequest(request, f.getAsyncHandler(), f, true);
    }

    // FIXME is this useful? Can't we do that when building the request?
    private final boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        return request.getMethod().equals(HttpMethod.GET.name()) && asyncHandler instanceof WebSocketUpgradeHandler;
    }

    private Channel getCachedChannel(NettyResponseFuture<?> future, URI uri, ConnectionPoolKeyStrategy poolKeyGen, ProxyServer proxyServer) {

        if (future != null && future.reuseChannel() && future.channel() != null) {
            return future.channel();
        } else {
            URI connectionKeyUri = proxyServer != null ? proxyServer.getURI() : uri;
            return channels.lookupInCache(connectionKeyUri, poolKeyGen);
        }
    }

    private <T> ListenableFuture<T> sendRequestWithCachedChannel(Channel channel, Request request, URI uri, ProxyServer proxy, NettyResponseFuture<T> future,
            AsyncHandler<T> asyncHandler) throws IOException {
        NettyRequest nettyRequest = null;

        if (future == null) {
            nettyRequest = NettyRequests.newNettyRequest(config, request, uri, false, proxy);
            future = NettyResponseFutures.newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, config, proxy);
        } else {
            nettyRequest = NettyRequests.newNettyRequest(config, request, uri, future.isConnectAllowed(), proxy);
            future.setNettyRequest(nettyRequest);
        }
        future.setState(NettyResponseFuture.STATE.POOLED);
        future.attachChannel(channel, false);

        LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, nettyRequest.getHttpRequest());
        Channels.setDefaultAttribute(channel, future);

        try {
            writeRequest(channel, config, future);
        } catch (Exception ex) {
            LOGGER.debug("writeRequest failure", ex);
            if (ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                LOGGER.debug("SSLEngine failure", ex);
                future = null;
            } else {
                try {
                    asyncHandler.onThrowable(ex);
                } catch (Throwable t) {
                    LOGGER.warn("doConnect.writeRequest()", t);
                }
                IOException ioe = new IOException(ex.getMessage());
                ioe.initCause(ex);
                throw ioe;
            }
        }
        return future;
    }

    private ChannelFuture connect(Request request, URI uri, ProxyServer proxy, Bootstrap bootstrap) {
        InetSocketAddress remoteAddress;
        if (request.getInetAddress() != null) {
            remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));
        } else if (proxy == null || ProxyUtils.avoidProxy(proxy, uri.getHost())) {
            remoteAddress = new InetSocketAddress(AsyncHttpProviderUtils.getHost(uri), AsyncHttpProviderUtils.getPort(uri));
        } else {
            remoteAddress = new InetSocketAddress(proxy.getHost(), proxy.getPort());
        }

        if (request.getLocalAddress() != null) {
            return bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
        } else {
            return bootstrap.connect(remoteAddress);
        }
    }

    private <T> ListenableFuture<T> sendRequestWithNewChannel(Request request, URI uri, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler,
            boolean reclaimCache) throws IOException {

        boolean useSSl = isSecure(uri) && proxy == null;

        // Do not throw an exception when we need an extra connection for a redirect
        // FIXME why? This violate the max connection per host handling, right?
        boolean acquiredConnection = !reclaimCache && channels.acquireConnection(asyncHandler);
        Bootstrap bootstrap = channels.getBootstrap(request.getUrl(), useSSl);

        NettyConnectListener<T> cl = new NettyConnectListener.Builder<T>(config, this, request, asyncHandler, future).build(uri);

        ChannelFuture channelFuture;
        try {
            channelFuture = connect(request, uri, proxy, bootstrap);

        } catch (Throwable t) {
            if (acquiredConnection) {
                channels.releaseFreeConnections();
            }
            channels.abort(cl.future(), t.getCause() == null ? t : t.getCause());
            return cl.future();
        }

        channelFuture.addListener(cl);

        LOGGER.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", cl.future().getNettyRequest().getHttpRequest(), channelFuture.channel());

        if (!cl.future().isCancelled() || !cl.future().isDone()) {
            channels.registerChannel(channelFuture.channel());
            cl.future().attachChannel(channelFuture.channel(), false);
        }
        return cl.future();
    }

    public <T> ListenableFuture<T> sendRequest(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future, boolean reclaimCache) throws IOException {

        if (closed.get()) {
            throw new IOException("Closed");
        }

        // FIXME really useful? Why not do this check when building the request?
        if (request.getUrl().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        URI uri = config.isUseRawUrl() ? request.getRawURI() : request.getURI();
        ProxyServer proxy = ProxyUtils.getProxyServer(config, request);
        Channel channel = getCachedChannel(future, uri, request.getConnectionPoolKeyStrategy(), proxy);

        if (channel != null && channel.isOpen() && channel.isActive()) {
            return sendRequestWithCachedChannel(channel, request, uri, proxy, future, asyncHandler);
        } else {
            return sendRequestWithNewChannel(request, uri, proxy, future, asyncHandler, reclaimCache);
        }
    }

    private void sendFileBody(Channel channel, NettyFileBody fileBody, NettyResponseFuture<?> future) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(fileBody.getFile(), "r");

        try {
            ChannelFuture writeFuture;
            if (Channels.getSslHandler(channel) != null) {
                writeFuture = channel.write(new ChunkedFile(raf, fileBody.getOffset(), fileBody.getContentLength(), Constants.MAX_BUFFERED_BYTES), channel.newProgressivePromise());
            } else {
                FileRegion region = new DefaultFileRegion(raf.getChannel(), fileBody.getOffset(), fileBody.getContentLength());
                writeFuture = channel.write(region, channel.newProgressivePromise());
            }
            // FIXME probably useless in Netty 4
            writeFuture.addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
                public void operationComplete(ChannelProgressiveFuture cf) {
                    try {
                        raf.close();
                    } catch (IOException e) {
                        LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                    }
                    super.operationComplete(cf);
                }
            });
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } catch (IOException ex) {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                }
            }
            throw ex;
        }
    }

    private boolean sendStreamAndExit(Channel channel, NettyInputStreamBody body, NettyResponseFuture<?> future) throws IOException {

        final InputStream is = body.getInputStream();

        if (future.isStreamWasAlreadyConsumed()) {
            if (is.markSupported())
                is.reset();
            else {
                LOGGER.warn("Stream has already been consumed and cannot be reset");
                return true;
            }
        } else {
            future.setStreamWasAlreadyConsumed(true);
        }

        channel.write(new ChunkedStream(is), channel.newProgressivePromise()).addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
            public void operationComplete(ChannelProgressiveFuture cf) {
                try {
                    is.close();
                } catch (IOException e) {
                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                }
                super.operationComplete(cf);
            }
        });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        return false;
    }

    public void sendBody(final Channel channel, final Body body, NettyResponseFuture<?> future) {
        Object msg;

        if (Channels.getSslHandler(channel) == null && body instanceof RandomAccessBody) {
            // FIXME also do something for multipart and use a ChunkedInput
            msg = new BodyFileRegion((RandomAccessBody) body);

        } else {
            msg = new BodyChunkedInput(body);

            BodyGenerator bg = future.getRequest().getBodyGenerator();
            if (bg instanceof FeedableBodyGenerator) {
                FeedableBodyGenerator.class.cast(bg).setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        channel.pipeline().get(ChunkedWriteHandler.class).resumeTransfer();
                    }
                });
            }
        }
        ChannelFuture writeFuture = channel.write(msg, channel.newProgressivePromise());

        writeFuture.addListener(new ProgressListener(config, false, future.getAsyncHandler(), future) {
            public void operationComplete(ChannelProgressiveFuture cf) {
                try {
                    body.close();
                } catch (IOException e) {
                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                }
                super.operationComplete(cf);
            }
        });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void configureTransferAdapter(AsyncHandler<?> handler, HttpRequest httpRequest) {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        for (Map.Entry<String, String> entries : httpRequest.headers()) {
            h.add(entries.getKey(), entries.getValue());
        }

        TransferCompletionHandler.class.cast(handler).headers(h);
    }

    private void scheduleReaper(NettyResponseFuture<?> future) {
        try {
            future.touch();
            int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, future.getRequest());
            int schedulePeriod = requestTimeout != -1 ? (config.getIdleConnectionTimeoutInMs() != -1 ? Math.min(requestTimeout, config.getIdleConnectionTimeoutInMs())
                    : requestTimeout) : config.getIdleConnectionTimeoutInMs();

            if (schedulePeriod != -1 && !future.isDone() && !future.isCancelled()) {
                FutureReaper reaperFuture = new FutureReaper(future, config, closed, channels);
                Future<?> scheduledFuture = config.reaper().scheduleAtFixedRate(reaperFuture, 0, schedulePeriod, TimeUnit.MILLISECONDS);
                reaperFuture.setScheduledFuture(scheduledFuture);
                future.setReaperFuture(reaperFuture);
            }
        } catch (RejectedExecutionException ex) {
            channels.abort(future, ex);
        }
    }

    public final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future) {
        try {
            // If the channel is dead because it was pooled and the remote
            // server decided to close it, we just let it go and the
            // closeChannel do it's work.
            if (!channel.isOpen() || !channel.isActive()) {
                return;
            }

            NettyRequest nettyRequest = future.getNettyRequest();
            HttpRequest httpRequest = nettyRequest.getHttpRequest();
            AsyncHandler<T> handler = future.getAsyncHandler();

            if (handler instanceof TransferCompletionHandler) {
                configureTransferAdapter(handler, httpRequest);
            }

            // Leave it to true.
            // FIXME That doesn't just leave to true, the set is always done? and what's the point of not having a is/get?
            if (future.getAndSetWriteHeaders(true)) {
                try {
                    if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
                        AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRequestSent();
                    }
                    channel.writeAndFlush(httpRequest, channel.newProgressivePromise()).addListener(
                            new ProgressListener(config, true, future.getAsyncHandler(), future));
                } catch (Throwable cause) {
                    // FIXME why not notify?
                    LOGGER.debug(cause.getMessage(), cause);
                    try {
                        channel.close();
                    } catch (RuntimeException ex) {
                        LOGGER.debug(ex.getMessage(), ex);
                    }
                    return;
                }
            }

            // FIXME OK, why? and what's the point of not having a is/get?
            if (future.getAndSetWriteBody(true)) {
                if (!httpRequest.getMethod().equals(HttpMethod.CONNECT)) {

                    NettyBody nettyBody = nettyRequest.getBody();

                    if (nettyBody instanceof NettyFileBody) {
                        sendFileBody(channel, (NettyFileBody) nettyBody, future);

                    } else if (nettyBody instanceof NettyInputStreamBody) {
                        if (sendStreamAndExit(channel, (NettyInputStreamBody) nettyBody, future))
                            // FIXME why bypassing scheduling the reaper when using a stream?
                            return;

                    } else if (nettyBody instanceof NettyMultipartBody) {
                        // FIXME use dedicated implementation
                        sendBody(channel, NettyMultipartBody.class.cast(nettyBody).getMultipartBody(), future);

                    } else if (nettyBody instanceof NettyBodyBody) {
                        sendBody(channel, NettyBodyBody.class.cast(nettyBody).getBody(), future);
                    }
                }
            }

        } catch (Throwable ioe) {
            try {
                channel.close();
            } catch (RuntimeException ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }

        scheduleReaper(future);
    }

    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, ChannelHandlerContext ctx) throws IOException {
        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
        }
        channels.drainChannel(ctx, future);
        sendNextRequest(newRequest, future);
    }
}
