package fk.prof.nfr.dropwizard.server;

import com.codahale.metrics.annotation.Timed;
import fk.prof.nfr.RndGen;
import org.glassfish.jersey.server.ChunkedOutput;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Path("/load-gen-app/jetty")
@Produces(MediaType.APPLICATION_JSON)
public class JettyFlowDwResource {

    private final RndGen rndm = new RndGen();

    private final Executor executor = Executors.newCachedThreadPool();

    private final ExecutorService fixedExecutor = Executors.newFixedThreadPool(2);

    @GET
    @Timed
    @Path("/simple")
    public String simple(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                         @QueryParam("delay") @DefaultValue("1000") Integer ms) throws Exception {
        Thread.sleep(ms);
        System.out.println("-- Executing handler --");
        return rndm.getString(sz);
    }

    @GET
    @Timed
    @Path("/dw-async-complete")
    public void asyncThenComplete(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                                  @QueryParam("delay") @DefaultValue("1000") Integer ms,
                                  @Suspended AsyncResponse async) throws Exception {

        Supplier<Integer> supplier = () -> {
            try {
                Thread.sleep(ms / 3);
            } catch (InterruptedException e) {}
            return sz;
        };

        CompletableFuture<Integer> f1 = CompletableFuture.supplyAsync(supplier, fixedExecutor);
        CompletableFuture<Integer> f2 = CompletableFuture.supplyAsync(supplier, fixedExecutor);

        CompletableFuture.allOf(f1, f2).whenComplete((r,t) -> executor.execute(() -> {
            String response = null;
            try {
                Thread.sleep(ms / 3);
            } catch (InterruptedException e) {
                response = e.getMessage();
            }

            if(response == null) {
                response = rndm.getString(sz);
            }

            System.out.println("-- Resuming task --");
            async.resume(response);
        }));

        System.out.println("-- Executing handler --");
    }

    @GET
    @Timed
    @Path("/dw-async-timeout")
    public void asyncThenTimeout(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                                  @QueryParam("delay") @DefaultValue("35000") Integer ms,
                                  @Suspended AsyncResponse async) throws Exception {
        executor.execute(() -> {
            String response = null;
            try {
                Thread.sleep(120000);
            } catch (InterruptedException e) {
                response = e.getMessage();
            }

            if(response == null) {
                response = rndm.getString(sz);
            }

            System.out.println("-- Resuming task --");
            async.resume(response);
        });

        System.out.println("-- Executing handler --");
    }

    @GET
    @Timed
    @Path("/dw-async-error")
    public void asyncThenError(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                                  @QueryParam("delay") @DefaultValue("1000") Integer ms,
                                  @Suspended AsyncResponse async) throws Exception {
        executor.execute(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
            }

            async.resume(new BadRequestException("bad request"));
        });
    }

    @GET
    @Timed
    @Path("/dw-async-runtime-error")
    public void asyncThenRuntimeError(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                               @QueryParam("delay") @DefaultValue("1000") Integer ms,
                               @Suspended AsyncResponse async) throws Exception {
        throw new RuntimeException("Throwing error to test error case");
    }

    @GET
    @Timed
    @Path("/dw-chunked")
    public ChunkedOutput<String> chunked(@QueryParam("sz") @DefaultValue("1000") Integer sz,
                                 @QueryParam("delay") @DefaultValue("1000") Integer ms) throws Exception {
        ChunkedOutput<String> resp = new ChunkedOutput<>(String.class);

        executor.execute(() -> {
            for(int i = 0; i < 2; i++) {
                try {
                    Thread.sleep(ms);
                    System.out.println("-- Writing to chunked output --");
                    resp.write(rndm.getString(sz / 2));

                    if(i + 1 == 2) {
                        resp.close();
                    }
                } catch (InterruptedException | IOException e) {
                }
            }
        });
        System.out.println("-- Executing handler --");
        return resp;
    }
}
