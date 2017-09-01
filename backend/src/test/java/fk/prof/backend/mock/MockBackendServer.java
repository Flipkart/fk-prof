package fk.prof.backend.mock;

import com.google.protobuf.util.JsonFormat;
import fk.prof.backend.exception.HttpFailure;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.http.HttpHelper;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import recording.Recorder;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Created by gaurav.ashok on 31/08/17.
 */
public class MockBackendServer {

    private static Logger logger = LoggerFactory.getLogger(MockBackendServer.class);

    public static void main(String[] args) {

        if(args.length < 1) {
            logger.info("Help: backend_server <local_ip>");
            return;
        }

        final String localIp = args[0];

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MockHttpVerticle(localIp));
    }

    static class MockHttpVerticle extends AbstractVerticle {

        final String localIp;
        final int port = 8080;

        MockHttpVerticle(String localIp) {
            this.localIp = localIp;
        }

        @Override
        public void start(Future<Void> fut) throws Exception {
            Router router = Router.router(vertx);

            HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.AGGREGATOR_POST_PROFILE,
                this::handlePostProfile);

            HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.BACKEND_POST_ASSOCIATION,
                BodyHandler.create().setBodyLimit(1024 * 10), this::handlePostAssociation);

            HttpHelper.attachHandlersToRoute(router, HttpMethod.POST, ApiPathConstants.BACKEND_POST_POLL,
                BodyHandler.create().setBodyLimit(1024 * 100), this::handlePostPoll);

            HttpHelper.attachHandlersToRoute(router, HttpMethod.GET, ApiPathConstants.BACKEND_HEALTHCHECK, this::handleGetHealth);

            HttpServerOptions serverOptions = new HttpServerOptions();
            serverOptions.setCompressionSupported(true);
            serverOptions.setIdleTimeout(300);
            serverOptions.setTcpKeepAlive(true);

            vertx.createHttpServer(serverOptions)
                .requestHandler(router::accept)
                .listen(port, http -> completeStartup(http, fut));
        }

        public void handlePostProfile(RoutingContext context) {
            context.response().endHandler(v -> {
               logger.info("post profile: response end");
            });

            context.request().handler(buf -> {
                logger.info("post profile: received data");
            }).exceptionHandler(ex -> {
                logger.error("post profile: exception", ex);
            }).endHandler(v -> {
                logger.info("post profile: request end");
            });
        }

        public void handlePostAssociation(RoutingContext context) {
            try {
                Recorder.RecorderInfo recorderInfo = ProtoUtil.buildProtoFromBuffer(Recorder.RecorderInfo.parser(), context.getBody());
                Recorder.ProcessGroup processGroup = RecorderProtoUtil.mapRecorderInfoToProcessGroup(recorderInfo);

                logger.info("association\n" + JsonFormat.printer().print(processGroup));

                byte[] bytes = Recorder.AssignedBackend.newBuilder().setHost(localIp).setPort(port).build().toByteArray();

                context.response().end(Buffer.buffer(bytes));
            }
            catch (Exception ex) {
                HttpFailure httpFailure = HttpFailure.failure(ex);
                HttpHelper.handleFailure(context, httpFailure);
            }
        }

        public void handlePostPoll(RoutingContext context) {

            logger.info("poll");
            String now = ZonedDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            try {
                Recorder.PollReq pollReq = ProtoUtil.buildProtoFromBuffer(Recorder.PollReq.parser(), context.getBody());

                Recorder.PollRes.Builder builder = Recorder.PollRes.newBuilder()
                    .setControllerId(111)
                    .setControllerVersion(1)
                    .setLocalTime(now);

                if(pollReq.getWorkLastIssued().getWorkState().equals(Recorder.WorkResponse.WorkState.complete)) {
                    builder.setAssignment(
                        Recorder.WorkAssignment.newBuilder()
                            .setDelay(30)
                            .setDescription("cpu sample")
                            .setDuration(300)
                            .setIssueTime(now)
                            .addWork(
                                Recorder.Work.newBuilder()
                                    .setWType(Recorder.WorkType.cpu_sample_work)
                                    .setCpuSample(Recorder.CpuSampleWork.newBuilder()
                                        .setFrequency(67)
                                        .setMaxFrames(128))));
                }

                byte[] bytes = builder.build().toByteArray();
                context.response().end(Buffer.buffer(bytes));

            } catch (Exception ex) {
                HttpFailure httpFailure = HttpFailure.failure(ex);
                HttpHelper.handleFailure(context, httpFailure);
            }
        }

        public void handleGetHealth(RoutingContext context) {
            logger.info("someone is concerned about my health");
            context.response().end("Have fun while testing.");
        }

        private void completeStartup(AsyncResult<HttpServer> http, Future<Void> fut) {
            if (http.succeeded()) {
                logger.info("Startup complete");
                fut.complete();
            } else {
                logger.error("Startup failed", http.cause());
                fut.fail(http.cause());
            }
        }
    }
}
