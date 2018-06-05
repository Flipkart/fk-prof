package fk.prof.nfr.netty.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChannelHandler implements ChannelPoolHandler {

    private static Logger logger = LoggerFactory.getLogger(ChannelHandler.class);

    private final String remoteAddress;

    private AtomicInteger count = new AtomicInteger(0);

    public ChannelHandler(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    @Override
    public void channelReleased(Channel ch) throws Exception {
    }

    @Override
    public void channelAcquired(Channel ch) throws Exception {
    }

    @Override
    public void channelCreated(Channel ch) throws Exception {
        count.incrementAndGet();

        ChannelPipeline p = ch.pipeline();
        p.addLast("codec", new HttpClientCodec());
        p.addLast("aggregator", new HttpObjectAggregator(1048576));
        p.addLast("contentExtractor", new ResponseHandler());

        logger.debug("[ " + this.remoteAddress + "] channel CREATED. Total count : " + count.get());

        ch.closeFuture().addListener(f -> {
            count.decrementAndGet();
            logger.debug("[ " + this.remoteAddress + "] channel CLOSED. Total count : " + count.get());
        });
    }
}
