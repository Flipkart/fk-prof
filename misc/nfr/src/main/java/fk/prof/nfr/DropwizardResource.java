package fk.prof.nfr;

import com.codahale.metrics.annotation.Timed;
import io.dropwizard.hibernate.UnitOfWork;
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
public class DropwizardResource {

    private static Logger logger = LoggerFactory.getLogger(DropwizardResource.class);

    private final IOService svc;

    public DropwizardResource(IOService svc) {
        this.svc = svc;
    }

    @GET
    @Timed
    @UnitOfWork
    public Map<String, Object> doIO(@NotNull @QueryParam("id") Integer id) throws Exception {
        logger.info("io request: " + id);
        return svc.soSomeIOWork(id);
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
