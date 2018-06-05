package fk.prof.nfr.netty.server;

import fk.prof.nfr.IOService;
import fk.prof.nfr.RndGen;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.http.client.utils.URLEncodedUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Resource {

    private static Logger logger = LoggerFactory.getLogger(Resource.class);

    private final IOService.Async svc;

    private final List<InetSocketAddress> servers;

    private final RndGen rndm = new RndGen();

    public Resource(IOService.Async svc, List<InetSocketAddress> servers) {
        this.svc = svc;
        this.servers = servers;
    }

    public Future<?> handle(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        URI uri = new URI(request.uri());
        String path = uri.getPath();
        Map<String, String> queryParams = new HashMap<>();
        URLEncodedUtils.parse(uri, Charset.forName("utf-8"))
            .forEach(q -> queryParams.put(q.getName(), q.getValue()));

        switch (path) {
            case "/load-gen-app/io":
                int id = Integer.parseInt(queryParams.getOrDefault("id", "0"));
                return handle_io_req(id);
            case "/load-gen-app/io/data":
                int sz = Integer.parseInt(queryParams.getOrDefault("sz", "1000"));
                int delay = Integer.parseInt(queryParams.getOrDefault("delay", "1000"));
                return handle_io_data_req(sz, delay, ctx.executor());
            default:
                return ctx.executor().newFailedFuture(new RuntimeException("path: \"" + path + "\" not found."));
        }
    }

    private Future<Map<String, Object>> handle_io_req(int id) {
        int serverId = rndm.getInt(servers.size());
        InetSocketAddress address = servers.get(serverId);
        return svc.doWork(address.getHostString(), address.getPort(), id);
    }

    private Future<String> handle_io_data_req(int sz, int delay, EventExecutor executor) {
        Promise<String> promise = executor.newPromise();
        executor.schedule(() -> promise.setSuccess(rndm.getString(sz)), delay, TimeUnit.MILLISECONDS);
        return promise;
    }
}
