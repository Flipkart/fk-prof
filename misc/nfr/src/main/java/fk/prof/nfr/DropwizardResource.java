package fk.prof.nfr;

import com.codahale.metrics.annotation.Timed;
import com.mashape.unirest.http.Unirest;
import io.dropwizard.hibernate.UnitOfWork;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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

    private final String ip;

    private final int port;

    private final UserDAO userDAO;

    private Random rndm = new Random(121);

    public DropwizardResource(String driverIp, int driverPort, UserDAO userDAO) {
        this.ip = driverIp;
        this.port = driverPort;
        this.userDAO = userDAO;
    }

    @GET
    @Timed
    @UnitOfWork
    public Map<String, Object> doIO(@NotNull @QueryParam("id") Integer id) throws Exception {
        logger.info("io request: " + id);
        Map<String, Object> srvrResponse = new HashMap<>();

        long start = System.currentTimeMillis();

        Object httpResp, sqlResp;

        // http
        try {
            httpResp = Unirest.get(clientUri("/driver/data"))
                .header("accept", "application/json")
                .queryString("sz", dataSz())
                .queryString("delay", delay())
                .asString().getBody();
        } catch (Exception e) {
            httpResp = e.getMessage();
        }
        // http end

        // sql
        try {
            User u = userDAO.getById(id);
            if (u == null) {
                u = new User(id, 1);
            } else {
                u.setAccessId(u.getAccessId() + 1);
            }
            userDAO.createOrUpdate(u);
            sqlResp = u;
        } catch (Exception e) {
            sqlResp = e.getMessage();
        }
        // sql end

        srvrResponse.put("data", httpResp);
        srvrResponse.put("user", sqlResp);

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
        return Math.max(0, (int) (rndm.nextGaussian() * 128 + 1024));
    }

    private int delay() {
        return Math.max(0, (int) (rndm.nextGaussian() * 50 + 50));
    }

    private String clientUri(String path) {
        return "http://" + ip + ":" + port + path;
    }
}
