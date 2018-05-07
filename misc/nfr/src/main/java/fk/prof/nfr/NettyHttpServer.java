package fk.prof.nfr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.lifecycle.Managed;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

public class NettyHttpServer {

    private ChannelFuture channel;

    private final EventLoopGroup masterGroup;

    private final EventLoopGroup slaveGroup;

    private final EventExecutorGroup eventExecutor;

    private final int port;

    private final IOService svc;

    private final SessionFactory sessionFactory;

    private final ObjectMapper om;

    public NettyHttpServer(int port, IOService svc, SessionFactory sessionFactory) {
        this.masterGroup = new NioEventLoopGroup();
        this.slaveGroup = new NioEventLoopGroup();
        this.eventExecutor = new DefaultEventExecutorGroup(20);
        this.port = port;
        this.svc = svc;
        this.sessionFactory = sessionFactory;
        this.om = new ObjectMapper();
    }

    public void start() // #1
    {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));

        try {
            // #3
            final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(masterGroup, slaveGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() // #4
                {
                    @Override
                    public void initChannel(final SocketChannel ch)
                        throws Exception {
                        ch.pipeline().addLast("codec", new HttpServerCodec());
                        ch.pipeline().addLast("aggregator",
                            new HttpObjectAggregator(512 * 1024));
                        ch.pipeline().addLast(eventExecutor, "request",
                            new ChannelInboundHandlerAdapter() // #5
                            {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg)
                                    throws Exception {

                                    if (msg instanceof FullHttpRequest) {
                                        final FullHttpRequest request = (FullHttpRequest) msg;

                                        int start = request.uri().indexOf('=') + 1;
                                        int id = Integer.parseInt(request.uri().substring(start));

                                        String responseMessage = "";
                                        Transaction transaction = null;
                                        try (Session session = sessionFactory.openSession()) {
                                            ManagedSessionContext.bind(session);
                                            transaction = session.beginTransaction();
                                            responseMessage = om.writeValueAsString(svc.soSomeIOWork(id));
                                            transaction.commit();
                                        } catch (Exception e) {
                                            if (transaction != null) transaction.rollback();
                                        } finally {
                                            ManagedSessionContext.unbind(sessionFactory);
                                        }

                                        om.writeValueAsString(svc.soSomeIOWork(id));

                                        FullHttpResponse response = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1,
                                            HttpResponseStatus.OK,
                                            Unpooled.copiedBuffer(responseMessage.getBytes())
                                        );

                                        if (HttpUtil.isKeepAlive(request)) {
                                            response.headers().set(
                                                HttpHeaderNames.CONNECTION,
                                                HttpHeaderNames.CONNECTION
                                            );
                                        }
                                        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                                            "application/json");
                                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                                            responseMessage.length());

                                        ctx.writeAndFlush(response);
                                    } else {
                                        super.channelRead(ctx, msg);
                                    }
                                }

                                @Override
                                public void channelReadComplete(ChannelHandlerContext ctx)
                                    throws Exception {
                                    ctx.flush();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx,
                                    Throwable cause) throws Exception {
                                    ctx.writeAndFlush(new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                        Unpooled.copiedBuffer(cause.getMessage().getBytes())
                                    ));
                                }
                            });
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
            channel = bootstrap.bind(port).sync();
        } catch (final InterruptedException e) {
        }
    }

    public void shutdown()
    {
        slaveGroup.shutdownGracefully();
        masterGroup.shutdownGracefully();

        try {
            channel.channel().closeFuture().sync();
        } catch (InterruptedException e) {
        }
    }
}