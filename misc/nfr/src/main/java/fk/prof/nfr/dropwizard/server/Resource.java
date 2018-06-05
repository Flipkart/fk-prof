package fk.prof.nfr.dropwizard.server;

import com.codahale.metrics.annotation.Timed;
import fk.prof.nfr.IOService.Sync;
import fk.prof.nfr.RndGen;
import io.dropwizard.hibernate.UnitOfWork;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotNull;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/load-gen-app/io")
@Produces(MediaType.APPLICATION_JSON)
public class Resource {

    private static Logger logger = LoggerFactory.getLogger(Resource.class);

    private final Sync svc;

    private final List<InetSocketAddress> servers;

    private final RndGen rndm = new RndGen();

    public Resource(Sync svc, List<InetSocketAddress> servers) {
        this.svc = svc;
        this.servers = servers;
    }

    @GET
    @Timed
    @UnitOfWork
    public Map<String, Object> doIO(@NotNull @QueryParam("id") Integer id) throws Exception {
        int serverId = rndm.getInt(servers.size());
        InetSocketAddress address = servers.get(serverId);
        return svc.doWork(address.getHostString(), address.getPort(), id);
    }

    @GET
    @Path("/data")
    @Timed
    public String data(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                       @QueryParam("delay") @DefaultValue("1000") Integer ms) throws Exception {
        Thread.sleep(ms);
        return rndm.getString(sz);
    }

    @GET
    @Timed
    @Path("/ping")
    public String ping(@QueryParam("delay") @DefaultValue("1000") Integer ms) throws Exception {
        logger.info("ping");
        Thread.sleep(ms);
        return "OK";
    }
}
