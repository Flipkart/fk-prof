package fk.prof.nfr.netty.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class HttpServer {

    private final EventLoopGroup masterGroup;

    private final EventLoopGroup slaveGroup;

    private final int port;

    private final RequestHandler reqHandler;

    private ChannelFuture channel;

    public HttpServer(EventLoopGroup masterGroup, int port, RequestHandler reqHandler) {
        this.masterGroup = masterGroup;
        this.slaveGroup = new NioEventLoopGroup();
        this.port = port;
        this.reqHandler = reqHandler;
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        try {
            final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(masterGroup, slaveGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new RequestHandlerInitializer(reqHandler))
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
            channel = bootstrap.bind(port).sync();
        } catch (final InterruptedException e) {
        }
    }

    void shutdown() {
        slaveGroup.shutdownGracefully();
        masterGroup.shutdownGracefully();

        try {
            channel.channel().closeFuture().sync();
        } catch (InterruptedException e) {
        }
    }
}