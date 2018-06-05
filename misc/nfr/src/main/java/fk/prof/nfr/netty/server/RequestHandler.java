package fk.prof.nfr.netty.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import org.eclipse.jetty.http.HttpHeaderValue;

@Sharable
public class RequestHandler extends ChannelInboundHandlerAdapter {

    private final Resource resource;

    private final ObjectMapper om;

    public RequestHandler(Resource resource) {
        this.resource = resource;
        this.om = new ObjectMapper();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {
            final FullHttpRequest request = (FullHttpRequest) msg;
            Future<?> future = resource.handle(ctx, request);

            // release the request, no more use
            request.release();

            // write the response back when we have handled the request.
            future.addListener(f -> {
                String responseMessage;
                if (f.isSuccess()) {
                    responseMessage = om.writeValueAsString(f.getNow());
                } else {
                    responseMessage = String.valueOf(f.cause() == null ? "null" : f.cause().getMessage());
                }

                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                                                        Unpooled
                                                                            .copiedBuffer(responseMessage.getBytes()));

                boolean keepAlive = HttpUtil.isKeepAlive(request);
                if (keepAlive) {
                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
                }

                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseMessage.length());

                ChannelFuture flushFuture = ctx.writeAndFlush(response);

                if (!keepAlive) {
                    flushFuture.addListener(ChannelFutureListener.CLOSE);
                }
            });
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.writeAndFlush(new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            Unpooled.copiedBuffer(cause.getMessage().getBytes())
        ));
    }
}
