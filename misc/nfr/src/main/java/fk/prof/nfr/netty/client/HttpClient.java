package fk.prof.nfr.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPoolMap;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;

public class HttpClient {

    private Bootstrap bootstrap;

    private ChannelPoolMap<InetSocketAddress, SimpleChannelPool> poolMap;

    public HttpClient(EventLoopGroup masterGroup) {
        bootstrap = new Bootstrap()
            .group(masterGroup)
            .channel(NioSocketChannel.class);

        poolMap = new AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool>() {
            @Override
            protected SimpleChannelPool newPool(InetSocketAddress key) {
                return new SimpleChannelPool(bootstrap.remoteAddress(key),
                                             new ChannelHandler(key.getHostString() + ":" + key.getPort()));
            }
        };
    }

    public void doGet(String ip, int port, String path, Promise<String> responsePromise) {
        doGet(ip, port, path, responsePromise, true);
    }

    public void doGet(String ip, int port, String path, Promise<String> responsePromise, boolean keepAlive) {

        InetSocketAddress address = InetSocketAddress.createUnresolved(ip, port);

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
        request.headers().set(HttpHeaderNames.HOST, ip);
        if (!keepAlive) {
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        final SimpleChannelPool pool = poolMap.get(address);
        pool.acquire().addListener((Future<Channel> f) -> {
            if (f.isSuccess()) {
                Channel channel = f.getNow();

                // add response handler
                channel.pipeline().addLast("handler", new ResponseEndHandler(pool, responsePromise));

                // do request
                channel.writeAndFlush(request);
            } else {
                responsePromise.setFailure(f.cause());
            }
        });
    }
}
