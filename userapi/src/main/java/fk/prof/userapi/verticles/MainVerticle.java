package fk.prof.userapi.verticles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.model.StorageFactory;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.vertx.core.*;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Deploys instances of {@link HttpVerticle} based on the number provided in the config in the following structure:
 * <pre>
 * {@code
 * {
 * "http.port": 8082,
 * "http.instances": 1,
 * "req.timeout": 2000,
 * "storage":"S3",
 * }
 *  }
 * </pre>
 * Created by rohit.patiyal on 02/02/17.
 */
public class MainVerticle extends AbstractVerticle {
    public static Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        // register serializers
        registerSerializers(Json.mapper);
        registerSerializers(Json.prettyMapper);

        int numInstances = config().getInteger("http.instances");
        int profileRetentionDuration = config().getInteger("profile.retention.duration");

        List<Future> futureList = new ArrayList<>();
        for (int i = 0; i < numInstances; i++) {
            Future<String> future = Future.future();
            futureList.add(future);
            Verticle routerVerticle = new HttpVerticle(new ProfileStoreAPIImpl(vertx, StorageFactory.getAsyncStorage(config()), profileRetentionDuration));
            vertx.deployVerticle(routerVerticle, new DeploymentOptions().setConfig(config()), future.completer());
        }
        CompositeFuture.all(futureList).setHandler(event -> {
            if (event.succeeded()) {
                logger.info("Deployment of all HttpVerticles succeeded");
                startFuture.complete();
            } else {
                logger.error("Deployment of HttpVerticles failed, Cause from MainVerticle = " + event.cause());
                startFuture.fail(event.cause());
            }
        });
    }

    public void registerSerializers(ObjectMapper mapper) {
        // protobuf
        ProtoSerializers.registerSerializers(mapper);

        // java 8, datetime
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
    }
}
