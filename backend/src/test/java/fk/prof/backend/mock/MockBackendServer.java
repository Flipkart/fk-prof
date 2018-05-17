package fk.prof.backend.mock;

import fk.prof.backend.aggregator.AggregationWindow;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.exception.HttpFailure;
import fk.prof.backend.http.ApiPathConstants;
import fk.prof.backend.http.HttpHelper;
import fk.prof.backend.model.aggregation.AggregationWindowDiscoveryContext;
import fk.prof.backend.model.profile.RecordedProfileIndexes;
import fk.prof.backend.request.profile.ISingleProcessingOfProfileGate;
import fk.prof.backend.request.profile.RecordedProfileProcessor;
import fk.prof.backend.util.ProtoUtil;
import fk.prof.idl.Profile;
import fk.prof.idl.Recorder;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;
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
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by gaurav.ashok on 31/08/17.
 */
public class MockBackendServer {

    private static Logger logger = LoggerFactory.getLogger(MockBackendServer.class);

    static String localIp;
    static int delay;
    static int duration;

    public static void main(String[] args) {

        if (args.length < 4) {
            logger.info("Help: backend_server <local_ip> duration delay [io:threshold_ms|cpu:freq]");
            return;
        }

        localIp = args[0];
        duration = Integer.parseInt(args[1]);
        delay = Integer.parseInt(args[2]);

        String workSpecificArgs = args[3];

        WorkCreator wc;
        if (workSpecificArgs.startsWith("io")) {
            wc = new IOTracingWork(workSpecificArgs);
        } else if (workSpecificArgs.startsWith("cpu")) {
            wc = new CpuWork(workSpecificArgs);
        } else {
            throw new RuntimeException("work specific args invalid: " + workSpecificArgs);
        }

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MockHttpVerticle(localIp, wc));
    }

    public interface WorkCreator {
        WorkEntities.WorkAssignment getWorkAssignment();

        WorkEntities.Work getWork();
    }

    public static class CpuWork implements WorkCreator {

        int freq;
        final int flushThreshold = 100;
        final int maxFrame = 128;

        public CpuWork(String args) {
            try {
                freq = Integer.parseInt(args.split(":")[1]);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public WorkEntities.WorkAssignment getWorkAssignment() {

            String now = ZonedDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            return WorkEntities.WorkAssignment.newBuilder()
                .setDelay(delay)
                .setDescription("cpu sample")
                .setDuration(duration)
                .setIssueTime(now)
                .setWorkId(System.currentTimeMillis() / 100)
                .addWork(getWork())
                .build();
        }

        @Override
        public WorkEntities.Work getWork() {
            return WorkEntities.Work.newBuilder()
                .setWType(WorkEntities.WorkType.cpu_sample_work)
                .setCpuSample(WorkEntities.CpuSampleWork.newBuilder()
                    .setSerializationFlushThreshold(flushThreshold)
                    .setFrequency(freq)
                    .setMaxFrames(maxFrame)
                    .build())
                .build();
        }
    }

    public static class IOTracingWork implements WorkCreator {

        int threashold_ms;
        final int flushThreshold = 100;
        final int maxFrames = 128;

        public IOTracingWork(String args) {
            try {
                threashold_ms = Integer.parseInt(args.split(":")[1]);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public WorkEntities.WorkAssignment getWorkAssignment() {
            String now = ZonedDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            return WorkEntities.WorkAssignment.newBuilder()
                .setDelay(delay)
                .setDescription("io tracing")
                .setDuration(duration)
                .setIssueTime(now)
                .setWorkId(System.currentTimeMillis() / 100)
                .addWork(getWork()).build();
        }

        @Override
        public WorkEntities.Work getWork() {
            return WorkEntities.Work.newBuilder()
                .setWType(WorkEntities.WorkType.io_trace_work)
                .setIoTrace(WorkEntities.IOTraceWork.newBuilder()
                    .setLatencyThresholdMs(threashold_ms)
                    .setSerializationFlushThreshold(flushThreshold)
                    .setMaxFrames(maxFrames))
                .build();
        }
    }

    static class MockHttpVerticle extends AbstractVerticle {

        final String localIp;
        final WorkCreator wc;
        final int port = 8080;

        final Map<Long, AggregationWindow> windows = new ConcurrentHashMap<>();
        final AggregationWindowDiscoveryContext ctx = workId -> windows.get(workId);
        final ISingleProcessingOfProfileGate profileGate = new ISingleProcessingOfProfileGate() {
            @Override
            public void accept(long workId) throws AggregationFailure {
            }

            @Override
            public void finish(long workId) {
            }
        };

        MockHttpVerticle(String localIp, WorkCreator wc) {
            this.localIp = localIp;
            this.wc = wc;
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
            String remote = context.request().connection().remoteAddress().host();
            logger.info("post profile: " + remote);

            RecordedProfileProcessor profileProcessor = new RecordedProfileProcessor(context, ctx, profileGate, 1024 * 1024, 1024 * 1024);

            context.response().endHandler(v -> {
                profileProcessor.close();
                logger.info("post profile: response end: " + remote);
            });

            context.request()
                .handler(profileProcessor)
                .exceptionHandler(ex -> {
                    logger.error("post profile: exception: " + remote, ex);
                })
                .endHandler(v -> {
                    logger.info("post profile: request end: " + remote);
                    context.response().end();
                });
        }

        public void handlePostAssociation(RoutingContext context) {
            String remote = context.request().connection().remoteAddress().host();
            try {
                logger.info("association: " + remote);

                byte[] bytes = Recorder.AssignedBackend.newBuilder().setHost(localIp).setPort(port).build().toByteArray();

                context.response().end(Buffer.buffer(bytes));
            } catch (Exception ex) {
                HttpFailure httpFailure = HttpFailure.failure(ex);
                HttpHelper.handleFailure(context, httpFailure);
            }
        }

        public void handlePostPoll(RoutingContext context) {
            String remote = context.request().connection().remoteAddress().host();

            String now = ZonedDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            try {
                Recorder.PollReq pollReq = ProtoUtil.buildProtoFromBuffer(Recorder.PollReq.parser(), context.getBody());

                Recorder.PollRes.Builder builder = Recorder.PollRes.newBuilder()
                    .setControllerId(111)
                    .setControllerVersion(1)
                    .setLocalTime(now);

                if (pollReq.getWorkLastIssued().getWorkState().equals(WorkEntities.WorkResponse.WorkState.complete)) {
                    logger.info("poll NEW work: " + remote);
                    builder.setAssignment(wc.getWorkAssignment());

                    // create a aggregation window
                    windows.compute(wc.getWorkAssignment().getWorkId(), (k, v) -> {
                        if (v != null) {
                            throw new RuntimeException("agg window should have been null");
                        }
                        long[] workdIds = new long[]{wc.getWorkAssignment().getWorkId()};
                        return mockAggWindow(new AggregationWindow("a", "c", "p",
                            LocalDateTime.now(Clock.systemUTC()).plusSeconds(wc.getWorkAssignment().getDelay()),
                            wc.getWorkAssignment().getDuration(),
                            workdIds,
                            getRecordingPolicy()));
                    });
                } else {
                    logger.info("poll NO work, prev state: " + pollReq.getWorkLastIssued().getWorkState().name() + " : " + remote);
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

        private Profile.RecordingPolicy getRecordingPolicy() {
            return Profile.RecordingPolicy.newBuilder()
                .setCoveragePct(25)
                .setDuration(wc.getWorkAssignment().getDuration())
                .setMinHealthy(0)
                .setDescription("test work")
                .addWork(wc.getWork())
                .build();
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

        private AggregationWindow mockAggWindow(AggregationWindow w) {
            w = Mockito.spy(w);

            Mockito.doAnswer(inv -> {
//                inv.callRealMethod();

                // dump all content
                Recording.Wse wse = inv.getArgument(0);
                RecordedProfileIndexes indexes = inv.getArgument(1);
                if(wse.hasIoTraceEntry()) {
                    Recording.IOTraceWse io = wse.getIoTraceEntry();
                    for(Recording.IOTrace trace : io.getTracesList()) {
                        printIOActivity(indexes, trace, trace.getLatencyNs());
                        Recording.StackSample ss = trace.getStack();
                        for(int i = 0; i < trace.getStack().getFrameCount(); ++i) {
                            long mid = ss.getFrame(i).getMethodId();
                            System.out.println(indexes.getMethod(mid) + ": " + ss.getFrame(i).getLineNo());
                        }
                        System.out.println();
                    }
                }

                return null;
            }).when(w).aggregate(ArgumentMatchers.any(), ArgumentMatchers.any());

            return w;
        }

        private void printIOActivity(RecordedProfileIndexes index, Recording.IOTrace trace, Long latency) {
            String filename = "";

            switch (trace.getType()) {
                case socket_read:
                case socket_write:
                    filename = index.getFdInfo(trace.getFdId()).getSocketInfo().getAddress();
                    break;
                case file_write:
                case file_read:
                    filename = index.getFdInfo(trace.getFdId()).getFileInfo().getFilename();
            }

            switch (trace.getType()) {
                case socket_read:
                case file_read:
                    Recording.FdRead read = trace.getRead();
                    System.out.println("[" + filename + "]" + ", Read: " + read.getCount() + ", time: " +
                        (latency/1000000.0) + ", " + "timeout: " + read.getTimeout());
                    break;
                case socket_write:
                case file_write:
                    Recording.FdWrite write = trace.getWrite();
                    System.out.println("[" + filename + "]" + ", Write: " + write.getCount() + ", time: " +
                        (latency/1000000.0));
                    break;
            }
        }
    }
}
