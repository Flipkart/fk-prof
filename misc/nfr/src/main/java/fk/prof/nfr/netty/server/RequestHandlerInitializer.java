package fk.prof.nfr.netty.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class RequestHandlerInitializer extends ChannelInitializer<SocketChannel> {

    private final ChannelHandler requestHandler;

    public RequestHandlerInitializer(ChannelHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @Override
    protected void initChannel(final SocketChannel ch) throws Exception {
        ch.pipeline().addLast("codec", new HttpServerCodec());
        ch.pipeline().addLast("aggregator", new HttpObjectAggregator(1024 * 1024));
        ch.pipeline().addLast("request", requestHandler);
    }
}
