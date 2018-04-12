package fk.prof.nfr.driver;

import com.codahale.metrics.annotation.Timed;
import fk.prof.nfr.RndGen;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Produces(MediaType.APPLICATION_JSON)
public class Resource {

    RndGen rndm = new RndGen();

    @GET
    @Timed
    @Path("/data")
    public String data(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                       @QueryParam("delay") @DefaultValue("1000") Integer ms) throws Exception {
        Thread.sleep(ms);
        return rndm.getString(sz);
    }
}
