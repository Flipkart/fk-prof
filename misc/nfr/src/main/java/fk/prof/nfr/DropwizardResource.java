package fk.prof.nfr;

import com.codahale.metrics.annotation.Timed;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import io.dropwizard.hibernate.UnitOfWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Path("/load-gen-app/io")
@Produces(MediaType.APPLICATION_JSON)
public class DropwizardResource {

    private static Logger logger = LoggerFactory.getLogger(DropwizardResource.class);

    private final String ip;
    private final int port;
    private final UserDAO userDAO;

    private Random rndm = new Random(121);

    public DropwizardResource(String ip, int port, UserDAO userDAO) {
        this.ip = ip;
        this.port = port;
        this.userDAO = userDAO;
    }

    @GET
    @Timed
    @UnitOfWork
    public Map<String, Object> doIO(@NotNull @QueryParam("id") Integer id) throws Exception {
        logger.info("io request: " + id);
        Map<String, Object> srvrResponse = new HashMap<>();

        long start = System.currentTimeMillis();

        // http
        HttpResponse<String> response =  Unirest.get(clientUri("data"))
            .header("accept", "application/json")
            .queryString("sz", dataSz())
            .queryString("delay", delay())
            .asString();
        // http end

        // sql
        User u = userDAO.getById(id);
        if(u == null) {
            u = new User(id, 1);
        } else {
            u.setAccessId(u.getAccessId() + 1);
        }
        userDAO.createOrUpdate(u);
        // sql end

        srvrResponse.put("data", response.getBody());
        srvrResponse.put("user", u);

        long end = System.currentTimeMillis();
        srvrResponse.put("time", end - start);

        return srvrResponse;
    }

    @GET
    @Timed
    @Path("/ping")
    public String ping(@QueryParam("delay") @DefaultValue("1000") Integer ms) throws Exception {
        logger.info("ping");
        Thread.sleep(ms);
        return "OK";
    }

    private int dataSz() {
        return Math.max(0, (int)(rndm.nextGaussian() * 128 + 1024));
    }

    private int delay() {
        return Math.max(0, (int)(rndm.nextGaussian() * 50 + 50));
    }

    private String clientUri(String path) {
        return "http://" + ip + ":" + port + path;
    }
}
