package fk.prof.nfr;

import fk.prof.nfr.netty.client.HttpClient;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Promise;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

public class NettyHttpClientTest {

    @Test
    public void testClient() throws Exception {
        EventLoopGroup defaultGroup = new DefaultEventLoopGroup(2);
        EventLoopGroup nioGroup = new NioEventLoopGroup(1);

        HttpClient client = new HttpClient(nioGroup);

        for (int i = 0; i < 5; i++) {
            Promise<String> response1 = defaultGroup.next().newPromise();
            client.doGet("10.47.0.101", 80, "/v1/buckets/fk-prof-rec", response1);

            CountDownLatch latch1 = new CountDownLatch(1);
            response1.addListener(f -> latch1.countDown());
            latch1.await();

            System.out.println(response1.get());
        }
    }
}
