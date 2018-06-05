package fk.prof.nfr.netty.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseEndHandler extends ChannelInboundHandlerAdapter {

    private static Logger logger = LoggerFactory.getLogger(ResponseEndHandler.class);

    private final ChannelPool pool;

    Promise<String> promise;

    public ResponseEndHandler(ChannelPool pool, Promise<String> promise) {
        this.pool = pool;
        this.promise = promise;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof String) {
            promise.setSuccess((String) msg);
            ctx.pipeline().remove(this);

            pool.release(ctx.channel());
        } else {
            logger.error("** Not expecting a msg of type other than String **");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        promise.setFailure(cause);
    }
}
