package fk.prof.backend;

import com.google.protobuf.CodedOutputStream;
import fk.prof.aggregation.model.*;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.backend.aggregator.AggregationWindow;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.deployer.impl.BackendHttpVerticleDeployer;
import fk.prof.backend.mock.MockProfileObjects;
import fk.prof.backend.model.aggregation.ActiveAggregationWindows;
import fk.prof.backend.model.assignment.AssociatedProcessGroups;
import fk.prof.backend.model.assignment.impl.AssociatedProcessGroupsImpl;
import fk.prof.backend.model.election.LeaderReadContext;
import fk.prof.backend.model.election.impl.InMemoryLeaderStore;
import fk.prof.backend.model.aggregation.impl.ActiveAggregationWindowsImpl;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import recording.Recorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import static org.mockito.Mockito.spy;

@RunWith(VertxUnitRunner.class)
public class ProfileApiTest {

  private static Vertx vertx;
  private static ConfigManager configManager;
  private static Integer port;
  private static ActiveAggregationWindows activeAggregationWindows;
  private static AssociatedProcessGroups associatedProcessGroups;
  private static LeaderReadContext leaderReadContext;

  private static AtomicLong workIdCounter = new AtomicLong(0);

  @BeforeClass
  public static void setUp(TestContext context) throws Exception {
    ConfigManager.setDefaultSystemProperties();
    Configuration config = spy(ConfigManager.loadConfig(ProfileApiTest.class.getClassLoader().getResource("config.json").getFile()));

    vertx = Vertx.vertx(new VertxOptions(config.getVertxOptions()));
    activeAggregationWindows = new ActiveAggregationWindowsImpl();
    leaderReadContext = new InMemoryLeaderStore(config.getIpAddress(), config.getLeaderHttpServerOpts().getPort());
    associatedProcessGroups = new AssociatedProcessGroupsImpl(config.getRecorderDefunctThresholdSecs());
    port = config.getBackendHttpServerOpts().getPort();

    VerticleDeployer backendVerticleDeployer = new BackendHttpVerticleDeployer(vertx, config, leaderReadContext, activeAggregationWindows, associatedProcessGroups);
    backendVerticleDeployer.deploy();
    //Wait for some time for verticles to be deployed
    Thread.sleep(1000);
  }

  @AfterClass
  public static void tearDown(TestContext context) {
    vertx.close();
  }

  @Test(timeout = 5000)
  public void testWithValidSingleProfile(TestContext context) {
    long workId = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, 60));

    final Async async = context.async();
    Future<ResponsePayload> future = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile());
    future.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        context.assertEquals(200, ar.result().statusCode);
        //Validate aggregation
        AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId);
        FinalizedAggregationWindow actual = aggregationWindow.finalizeEntity();
        FinalizedCpuSamplingAggregationBucket expectedAggregationBucket = getExpectedAggregationBucketOfPredefinedSamples();
        Map<AggregatedProfileModel.WorkType, Integer> expectedSamplesMap = new HashMap<>();
        expectedSamplesMap.put(AggregatedProfileModel.WorkType.cpu_sample_work, 3);
        FinalizedProfileWorkInfo expectedWorkInfo = getExpectedWorkInfo(actual.getDetailsForWorkId(workId).getStartedAt(),
            actual.getDetailsForWorkId(workId).getEndedAt(), expectedSamplesMap);
        Map<Long, FinalizedProfileWorkInfo> expectedWorkLookup = new HashMap<>();
        expectedWorkLookup.put(workId, expectedWorkInfo);
        FinalizedAggregationWindow expected = new FinalizedAggregationWindow("a", "c", "p",
            awStart, null, 30 * 60,
            expectedWorkLookup, expectedAggregationBucket);
        context.assertTrue(expected.equals(actual));
        async.complete();
      }
    });
  }

  @Test(timeout = 5000)
  public void testWithValidMultipleProfiles(TestContext context) {
    long workId1 = workIdCounter.incrementAndGet();
    long workId2 = workIdCounter.incrementAndGet();
    long workId3 = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    AggregationWindow aw = new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId1, workId2, workId3}, 60);
    activeAggregationWindows.associateAggregationWindow(new long[] {workId1, workId2, workId3}, aw);
    List<Recorder.Wse> wseList = getMockWseEntriesForMultipleProfiles();

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId1), Arrays.asList(wseList.get(0)));
    Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId2), Arrays.asList(wseList.get(1)));
    Future<ResponsePayload> f3 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId3), Arrays.asList(wseList.get(2)));
    CompositeFuture.all(Arrays.asList(f1, f2, f3)).setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        List<ResponsePayload> responsePayloads = ar.result().list();
        Set<Integer> statuses = responsePayloads.stream().map(rp -> rp.statusCode).collect(Collectors.toSet());
        context.assertEquals(1, statuses.size());
        context.assertTrue(statuses.contains(200));

        AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId1);
        FinalizedAggregationWindow actual = aggregationWindow.finalizeEntity();
        FinalizedCpuSamplingAggregationBucket expectedAggregationBucket = getExpectedAggregationBucketOfPredefinedSamples();

        Map<AggregatedProfileModel.WorkType, Integer> expectedSamplesMap1 = new HashMap<>();
        expectedSamplesMap1.put(AggregatedProfileModel.WorkType.cpu_sample_work, 1);
        FinalizedProfileWorkInfo expectedWorkInfo1 = getExpectedWorkInfo(actual.getDetailsForWorkId(workId1).getStartedAt(),
            actual.getDetailsForWorkId(workId1).getEndedAt(), expectedSamplesMap1);

        Map<AggregatedProfileModel.WorkType, Integer> expectedSamplesMap2 = new HashMap<>();
        expectedSamplesMap2.put(AggregatedProfileModel.WorkType.cpu_sample_work, 1);
        FinalizedProfileWorkInfo expectedWorkInfo2 = getExpectedWorkInfo(actual.getDetailsForWorkId(workId2).getStartedAt(),
            actual.getDetailsForWorkId(workId2).getEndedAt(), expectedSamplesMap2);

        Map<AggregatedProfileModel.WorkType, Integer> expectedSamplesMap3 = new HashMap<>();
        expectedSamplesMap3.put(AggregatedProfileModel.WorkType.cpu_sample_work, 1);
        FinalizedProfileWorkInfo expectedWorkInfo3 = getExpectedWorkInfo(actual.getDetailsForWorkId(workId3).getStartedAt(),
            actual.getDetailsForWorkId(workId3).getEndedAt(), expectedSamplesMap3);

        Map<Long, FinalizedProfileWorkInfo> expectedWorkLookup = new HashMap<>();
        expectedWorkLookup.put(workId1, expectedWorkInfo1);
        expectedWorkLookup.put(workId2, expectedWorkInfo2);
        expectedWorkLookup.put(workId3, expectedWorkInfo3);

        FinalizedAggregationWindow expected = new FinalizedAggregationWindow("a", "c", "p",
            awStart, null, 30 * 60,
            expectedWorkLookup, expectedAggregationBucket);

        context.assertTrue(expected.equals(actual));
        async.complete();
      }
    });

  }

  @Test(timeout = 5000)
  public void testWithProfileWithoutEndMarker(TestContext context) {
    long workId = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, 60));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
        HeaderPayloadStrategy.VALID, WsePayloadStrategy.VALID, true, 0);
    f1.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        context.assertEquals(400, f1.result().statusCode);
        AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId);
        FinalizedAggregationWindow actual = aggregationWindow.expireWindow(activeAggregationWindows);
        context.assertNotNull(actual.getEndedAt());
        AggregationState aggregationState = actual.getDetailsForWorkId(workId).getState();
        context.assertEquals(AggregationState.INCOMPLETE, aggregationState);
        async.complete();
      }
    });
  }

  @Test(timeout = 20000)
  public void testProfileStateWhenIdleTimeoutOccurs(TestContext context) {
    long workId = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, 60));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
        HeaderPayloadStrategy.VALID, WsePayloadStrategy.VALID, true, 10000);
    f1.setHandler(ar -> {
      if (ar.failed()) {
        AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId);
        FinalizedAggregationWindow actual = aggregationWindow.expireWindow(activeAggregationWindows);
        context.assertNotNull(actual.getEndedAt());
        AggregationState aggregationState = actual.getDetailsForWorkId(workId).getState();
        context.assertEquals(AggregationState.INCOMPLETE, aggregationState);
        async.complete();
      } else {
        context.fail("This request should have failed because of idle timeout");
      }
    });
  }

  @Test(timeout = 5000)
  public void testWithCorruptProfileAndRetriedWithCorrectProfile(TestContext context) {
    long workId = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, 60));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
        HeaderPayloadStrategy.VALID, WsePayloadStrategy.INVALID_CHECKSUM, false, 0);
    f1.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        context.assertEquals(400, f1.result().statusCode);
        Future<ResponsePayload> f2 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
            HeaderPayloadStrategy.VALID, WsePayloadStrategy.VALID, false, 0);
        f2.setHandler(ar1 -> {
          if (ar1.failed()) {
            context.fail(ar1.cause());
          } else {
            context.assertEquals(200, ar1.result().statusCode);
            AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId);
            FinalizedAggregationWindow actual = aggregationWindow.expireWindow(activeAggregationWindows);
            context.assertNotNull(actual.getEndedAt());
            AggregationState aggregationState = actual.getDetailsForWorkId(workId).getState();
            context.assertEquals(AggregationState.RETRIED, aggregationState);
            async.complete();
          }
        });
      }
    });
  }

  @Test(timeout = 5000)
  public void testWithSameWorkIdProcessedConcurrently(TestContext context) {
    long workId1 = workIdCounter.incrementAndGet();
    long workId2 = workId1;
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    AggregationWindow aw = new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId1, workId2}, 60);
    activeAggregationWindows.associateAggregationWindow(new long[] {workId1}, aw);
    List<Recorder.Wse> wseList = getMockWseEntriesForMultipleProfiles();

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId1, 1), Arrays.asList(wseList.get(0)));
    Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId2, 2), Arrays.asList(wseList.get(1)));
    CompositeFuture.all(Arrays.asList(f1, f2)).setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        List<ResponsePayload> responsePayloads = ar.result().list();
        List<Integer> statuses = responsePayloads.stream().map(rp -> rp.statusCode).collect(Collectors.toList());
        context.assertTrue(statuses.contains(200));
        context.assertTrue(statuses.contains(400));
        context.assertTrue(responsePayloads.stream().anyMatch(rp -> rp.buffer.toString().toLowerCase().contains("profile is already being aggregated")));
        async.complete();
      }
    });
  }

  @Test(timeout = 5000)
  public void testWithSameProfileProcessedAgain(TestContext context) {
    long workId = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, 60));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile());
    f1.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        context.assertEquals(200, f1.result().statusCode);

        Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile());
        f2.setHandler(ar1 -> {
          if (ar1.failed()) {
            context.fail(ar1.cause());
          } else {
            context.assertEquals(400, ar1.result().statusCode);
            context.assertTrue(ar1.result().buffer.toString().toLowerCase().contains("error starting profile"));
            async.complete();
          }
        });
      }
    });
  }

  @Test(timeout = 5000)
  public void testWithEmptyBody(TestContext context) {
    final Async async = context.async();
    HttpClientRequest request = vertx.createHttpClient()
        .post(port, "localhost", "/profile")
        .handler(response -> {
          response.bodyHandler(buffer -> {
            //If any error happens, returned in formatted json, printing for debugging purposes
            System.out.println(buffer.toString());
            context.assertEquals(response.statusCode(), 400);
            context.assertTrue(buffer.toString().toLowerCase().contains("incomplete profile received"));
            async.complete();
          });
        });
    request.end();
  }

  @Test(timeout = 5000)
  public void testWithInvalidHeaderLength(TestContext context) {
    makeInvalidHeaderProfileRequest(context, HeaderPayloadStrategy.INVALID_HEADER_LENGTH, "invalid length for recording header");
  }

  @Test(timeout = 5000)
  public void testWithInvalidRecordingHeader(TestContext context) {
    makeInvalidHeaderProfileRequest(context, HeaderPayloadStrategy.INVALID_RECORDING_HEADER, "error while parsing recording header");
  }

  @Test(timeout = 5000)
  public void testWithInvalidHeaderChecksum(TestContext context) {
    makeInvalidHeaderProfileRequest(context, HeaderPayloadStrategy.INVALID_CHECKSUM, "checksum of header does not match");
  }

  @Test(timeout = 5000)
  public void testWithInvalidWorkId(TestContext context) {
    makeInvalidHeaderProfileRequest(context, HeaderPayloadStrategy.INVALID_WORK_ID, "not found, cannot continue receiving");
  }

  @Test(timeout = 5000)
  public void testWithInvalidWseLength(TestContext context) {
    makeInvalidWseProfileRequest(context, WsePayloadStrategy.INVALID_WSE_LENGTH, "invalid length for wse");
  }

  @Test(timeout = 5000)
  public void testWithInvalidWse(TestContext context) {
    makeInvalidWseProfileRequest(context, WsePayloadStrategy.INVALID_WSE, "error while parsing wse");
  }

  @Test(timeout = 5000)
  public void testWithInvalidWseChecksum(TestContext context) {
    makeInvalidWseProfileRequest(context, WsePayloadStrategy.INVALID_CHECKSUM, "checksum of wse does not match");
  }

  @Test(timeout = 5000)
  public void testAggregationWindowExpiryWhileReportingProfiles(TestContext context) throws InterruptedException {
    long workId1 = workIdCounter.incrementAndGet();
    long workId2 = workIdCounter.incrementAndGet();
    long workId3 = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    AggregationWindow aw = new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId1, workId2, workId3}, 60);
    activeAggregationWindows.associateAggregationWindow(new long[] {workId1, workId2, workId3}, aw);
    List<Recorder.Wse> wseList = getMockWseEntriesForMultipleProfiles();

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId1), Arrays.asList(wseList.get(0)));
    //make a long running profile request
    Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId2), Arrays.asList(wseList.get(1)), 2000);
    Thread.sleep(500);
    f1.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        try {
          context.assertEquals(200, f1.result().statusCode);

          AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId1);
          FinalizedAggregationWindow actual = aggregationWindow.expireWindow(activeAggregationWindows);
          context.assertNotNull(actual.getEndedAt());

          context.assertEquals(AggregationState.COMPLETED, actual.getDetailsForWorkId(workId1).getState());
          context.assertEquals(AggregationState.ABORTED, actual.getDetailsForWorkId(workId2).getState());
          context.assertEquals(AggregationState.SCHEDULED, actual.getDetailsForWorkId(workId3).getState());

          context.assertNull(activeAggregationWindows.getAssociatedAggregationWindow(workId1));
          context.assertNull(activeAggregationWindows.getAssociatedAggregationWindow(workId2));
          context.assertNull(activeAggregationWindows.getAssociatedAggregationWindow(workId3));

          async.complete();
        } catch (Exception ex) {
          context.fail(ex);
        }
      }
    });

  }

  private Future<ResponsePayload> makeValidProfileRequest(Recorder.RecordingHeader recordingHeader, List<Recorder.Wse> wseList) {
    return makeValidProfileRequest(recordingHeader, wseList, 0);
  }

  private Future<ResponsePayload> makeValidProfileRequest(Recorder.RecordingHeader recordingHeader, List<Recorder.Wse> wseList, int additionalDelayInMs) {
    return makeProfileRequest(vertx, port, recordingHeader, wseList, HeaderPayloadStrategy.VALID, WsePayloadStrategy.VALID, false, additionalDelayInMs);
  }

  private void makeInvalidHeaderProfileRequest(TestContext context, HeaderPayloadStrategy payloadStrategy, String errorToGrep) {
    long workId = workIdCounter.incrementAndGet();
    if (!payloadStrategy.equals(HeaderPayloadStrategy.INVALID_WORK_ID)) {
      activeAggregationWindows.associateAggregationWindow(new long[] {workId},
          new AggregationWindow("a", "c", "p", LocalDateTime.now(), 30 * 60, new long[]{workId}, 60));
    }
    final Async async = context.async();

    makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), null, payloadStrategy, WsePayloadStrategy.VALID, false, 0)
        .setHandler(ar -> {
          if(ar.failed()) {
            context.fail(ar.cause());
          }
          context.assertEquals(400, ar.result().statusCode);
          context.assertTrue(ar.result().buffer.toString().toLowerCase().contains(errorToGrep));
          async.complete();
        });
  }

  private void makeInvalidWseProfileRequest(TestContext context, WsePayloadStrategy payloadStrategy, String errorToGrep) {
    makeInvalidWseProfileRequest(context, payloadStrategy, errorToGrep, false);
  }

  private void makeInvalidWseProfileRequest(TestContext context, WsePayloadStrategy payloadStrategy, String errorToGrep, boolean skipEndMarker) {
    long workId = workIdCounter.incrementAndGet();
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", LocalDateTime.now(), 30 * 60, new long[]{workId}, 60));
    final Async async = context.async();

    makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(), HeaderPayloadStrategy.VALID, payloadStrategy, skipEndMarker, 0)
        .setHandler(ar -> {
          if(ar.failed()) {
            context.fail(ar.cause());
          }
          context.assertEquals(400, ar.result().statusCode);
          context.assertTrue(ar.result().buffer.toString().toLowerCase().contains(errorToGrep));

          AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId);
          FinalizedAggregationWindow actual = aggregationWindow.expireWindow(activeAggregationWindows);
          context.assertNotNull(actual.getEndedAt());
          AggregationState aggregationState = actual.getDetailsForWorkId(workId).getState();

          switch(payloadStrategy) {
            case VALID:
              context.assertEquals(AggregationState.COMPLETED, aggregationState);
              break;
            default:
              context.assertEquals(AggregationState.CORRUPT, aggregationState);
          }

          async.complete();
        });
  }

  private FinalizedCpuSamplingAggregationBucket getExpectedAggregationBucketOfPredefinedSamples() {
    MethodIdLookup expectedMethodIdLookup = new MethodIdLookup();
    expectedMethodIdLookup.getOrAdd("#Y ()");
    expectedMethodIdLookup.getOrAdd("#C ()");
    expectedMethodIdLookup.getOrAdd("#D ()");
    expectedMethodIdLookup.getOrAdd("#E ()");
    expectedMethodIdLookup.getOrAdd("#F ()");

    Map<String, CpuSamplingTraceDetail> expectedTraceDetailLookup = new HashMap<>();
    CpuSamplingTraceDetail expectedTraceDetail = new CpuSamplingTraceDetail();

    CpuSamplingFrameNode expectedRoot = expectedTraceDetail.getUnclassifiableRoot();
    CpuSamplingFrameNode y1 = expectedRoot.getOrAddChild(2, 10);
    CpuSamplingFrameNode c1 = y1.getOrAddChild(3, 10);
    CpuSamplingFrameNode d1 = c1.getOrAddChild(4, 10);
    CpuSamplingFrameNode c2 = d1.getOrAddChild(3, 10);
    CpuSamplingFrameNode d2 = c2.getOrAddChild(4, 10);
    CpuSamplingFrameNode e1 = d1.getOrAddChild(5, 10);
    CpuSamplingFrameNode c3 = e1.getOrAddChild(3, 10);
    CpuSamplingFrameNode d3 = c3.getOrAddChild(4, 10);
    CpuSamplingFrameNode f1 = e1.getOrAddChild(6, 10);
    CpuSamplingFrameNode c4 = f1.getOrAddChild(3, 10);
    for (int i = 0; i < 3; i++) {
      y1.incrementOnStackSamples();
    }
    for (int i = 0; i < 3; i++) {
      c1.incrementOnStackSamples();
    }
    for (int i = 0; i < 3; i++) {
      d1.incrementOnStackSamples();
    }
    c2.incrementOnStackSamples();
    d2.incrementOnStackSamples();
    d2.incrementOnCpuSamples();
    for (int i = 0; i < 2; i++) {
      e1.incrementOnStackSamples();
    }
    c3.incrementOnStackSamples();
    d3.incrementOnStackSamples();
    d3.incrementOnCpuSamples();
    f1.incrementOnStackSamples();
    c4.incrementOnStackSamples();
    c4.incrementOnCpuSamples();

    for (int i = 0; i < 3; i++) {
      expectedTraceDetail.incrementSamples();
    }
    expectedTraceDetailLookup.put("1", expectedTraceDetail);

    FinalizedCpuSamplingAggregationBucket expected = new FinalizedCpuSamplingAggregationBucket(
        expectedMethodIdLookup, expectedTraceDetailLookup
    );

    return expected;
  }

  private FinalizedProfileWorkInfo getExpectedWorkInfo(LocalDateTime startedAt, LocalDateTime endedAt, Map<AggregatedProfileModel.WorkType, Integer> samplesMap) {
    Map<String, Integer> expectedTraceCoverages = new HashMap<>();
    expectedTraceCoverages.put("1", 5);
    FinalizedProfileWorkInfo expectedProfileWorkInfo = new FinalizedProfileWorkInfo(1, null, AggregationState.COMPLETED,
        startedAt, endedAt, 60, expectedTraceCoverages, samplesMap);
    return expectedProfileWorkInfo;
  }

  public static Future<ResponsePayload> makeProfileRequest(Vertx vertx, int port, Recorder.RecordingHeader recordingHeader, List<Recorder.Wse> wseList, HeaderPayloadStrategy headerPayloadStrategy, WsePayloadStrategy wsePayloadStrategy, boolean skipEndMarker, int additionalDelayInMs) {
    Future<ResponsePayload> future = Future.future();
    vertx.executeBlocking(blockingFuture -> {
      try {
        HttpClientRequest request = vertx.createHttpClient()
            .post(port, "localhost", "/profile")
            .handler(response -> {
              response.bodyHandler(buffer -> {
                //If any error happens, returned in formatted json, printing for debugging purposes
                System.out.println(buffer.toString());
                blockingFuture.complete(new ResponsePayload(response.statusCode(), buffer));
              });
            })
            .exceptionHandler(th -> {
              if(!blockingFuture.isComplete()) {
                blockingFuture.fail(th);
              }
            })
            .setChunked(true);

        ByteArrayOutputStream requestStream = new ByteArrayOutputStream();
        if(recordingHeader != null) {
          writeMockHeaderToRequest(recordingHeader, requestStream, headerPayloadStrategy);
        }
        if(wseList != null) {
          writeMockWseEntriesToRequest(wseList, requestStream, wsePayloadStrategy);
        }
        if(!skipEndMarker) {
          writeEndMarkerToRequest(requestStream);
        }
        byte[] requestBytes = requestStream.toByteArray();
        chunkAndWriteToRequest(vertx, request, requestBytes, 32, additionalDelayInMs);
      } catch (IOException ex) {
        blockingFuture.fail(ex);
      }
    }, false, future.completer());

    return future;
  }

  public static void chunkAndWriteToRequest(Vertx vertx, HttpClientRequest request, byte[] requestBytes, int chunkSizeInBytes) {
    chunkAndWriteToRequest(vertx, request, requestBytes, chunkSizeInBytes, 0);
  }

  public static void chunkAndWriteToRequest(Vertx vertx, HttpClientRequest request, byte[] requestBytes, int chunkSizeInBytes, int additionalDelayInMs) {
    int i = 0;
    for (; (i + chunkSizeInBytes) <= requestBytes.length; i += chunkSizeInBytes) {
      writeChunkToRequest(request, requestBytes, i, i + chunkSizeInBytes);
    }
    writeChunkToRequest(request, requestBytes, i, requestBytes.length);
    if(additionalDelayInMs > 0) {
      vertx.executeBlocking(fut -> {
        try {
          Thread.sleep(additionalDelayInMs);
          request.end();
          fut.complete();
        } catch (Exception ex) {
          fut.fail(ex);
        }
      }, ar -> {});
    } else {
      request.end();
    }
  }

  public static void writeChunkToRequest(HttpClientRequest request, byte[] bytes, int start, int end) {
    request.write(Buffer.buffer(Arrays.copyOfRange(bytes, start, end)));
    try {
      Thread.sleep(10);
    } catch (Exception ex) {
    }
  }

  public static void writeMockHeaderToRequest(Recorder.RecordingHeader recordingHeader, ByteArrayOutputStream requestStream) throws IOException {
    writeMockHeaderToRequest(recordingHeader, requestStream, HeaderPayloadStrategy.VALID);
  }

  public static void writeMockHeaderToRequest(Recorder.RecordingHeader recordingHeader, ByteArrayOutputStream requestStream, HeaderPayloadStrategy payloadStrategy) throws IOException {
    int encodedVersion = 1;
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);

    byte[] recordingHeaderBytes = recordingHeader.toByteArray();
    codedOutputStream.writeUInt32NoTag(encodedVersion);

    if (payloadStrategy.equals(HeaderPayloadStrategy.INVALID_HEADER_LENGTH)) {
      codedOutputStream.writeUInt32NoTag(Integer.MAX_VALUE);
    } else {
      codedOutputStream.writeUInt32NoTag(recordingHeaderBytes.length);
    }

    if (payloadStrategy.equals(HeaderPayloadStrategy.INVALID_RECORDING_HEADER)) {
      byte[] invalidArr = Arrays.copyOfRange(recordingHeaderBytes, 0, recordingHeaderBytes.length);
      invalidArr[0] = invalidArr[1] = invalidArr[3] = 0;
      invalidArr[invalidArr.length - 1] = invalidArr[invalidArr.length - 2] = invalidArr[invalidArr.length - 3] = 0;
      codedOutputStream.writeByteArrayNoTag(invalidArr);
    } else {
      recordingHeader.writeTo(codedOutputStream);
    }
    codedOutputStream.flush();
    byte[] bytesWritten = outputStream.toByteArray();

    Checksum recordingHeaderChecksum = new Adler32();
    recordingHeaderChecksum.update(bytesWritten, 0, bytesWritten.length);
    long checksumValue = payloadStrategy.equals(HeaderPayloadStrategy.INVALID_CHECKSUM) ? 0 : recordingHeaderChecksum.getValue();
    codedOutputStream.writeUInt32NoTag((int) checksumValue);
    codedOutputStream.flush();
    outputStream.writeTo(requestStream);
  }

  public static void writeMockWseEntriesToRequest(List<Recorder.Wse> wseList, ByteArrayOutputStream requestStream) throws IOException {
    writeMockWseEntriesToRequest(wseList, requestStream, WsePayloadStrategy.VALID);
  }

  public static void writeMockWseEntriesToRequest(List<Recorder.Wse> wseList, ByteArrayOutputStream requestStream, WsePayloadStrategy payloadStrategy) throws IOException {
    if (wseList != null) {
      for (Recorder.Wse wse : wseList) {
        writeWseToRequest(wse, requestStream, payloadStrategy);
      }
    }
  }

  public static void writeWseToRequest(Recorder.Wse wse, ByteArrayOutputStream requestStream, WsePayloadStrategy payloadStrategy) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);
    byte[] wseBytes = wse.toByteArray();

    if (payloadStrategy.equals(WsePayloadStrategy.INVALID_WSE_LENGTH)) {
      codedOutputStream.writeUInt32NoTag(Integer.MAX_VALUE);
    } else {
      codedOutputStream.writeUInt32NoTag(wseBytes.length);
    }

    if (payloadStrategy.equals(WsePayloadStrategy.INVALID_WSE)) {
      byte[] invalidArr = Arrays.copyOfRange(wseBytes, 0, wseBytes.length);
      invalidArr[0] = invalidArr[1] = invalidArr[3] = 0;
      invalidArr[invalidArr.length - 1] = invalidArr[invalidArr.length - 2] = invalidArr[invalidArr.length - 3] = 0;
      codedOutputStream.writeByteArrayNoTag(invalidArr);
    } else {
      wse.writeTo(codedOutputStream);
    }
    codedOutputStream.flush();
    byte[] bytesWritten = outputStream.toByteArray();

    Checksum wseChecksum = new Adler32();
    wseChecksum.update(bytesWritten, 0, bytesWritten.length);
    long checksumValue = payloadStrategy.equals(WsePayloadStrategy.INVALID_CHECKSUM) ? 0 : wseChecksum.getValue();
    codedOutputStream.writeUInt32NoTag((int) checksumValue);
    codedOutputStream.flush();

    outputStream.writeTo(requestStream);
  }

  public static void writeEndMarkerToRequest(ByteArrayOutputStream requestStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);
    codedOutputStream.writeUInt32NoTag(0);
    codedOutputStream.flush();
    outputStream.writeTo(requestStream);
  }

  public static List<Recorder.Wse> getMockWseEntriesForSingleProfile() {
    List<Recorder.StackSample> samples = MockProfileObjects.getPredefinedStackSamples(1);
    Recorder.StackSampleWse ssw1 = Recorder.StackSampleWse.newBuilder()
        .addStackSample(samples.get(0))
        .addStackSample(samples.get(1))
        .build();
    Recorder.StackSampleWse ssw2 = Recorder.StackSampleWse.newBuilder()
        .addStackSample(samples.get(2))
        .build();

    Recorder.Wse wse1 = MockProfileObjects.getMockCpuWseWithStackSample(ssw1, null);
    Recorder.Wse wse2 = MockProfileObjects.getMockCpuWseWithStackSample(ssw2, ssw1);

    return Arrays.asList(wse1, wse2);
  }

  public static List<Recorder.Wse> getMockWseEntriesForMultipleProfiles() {
    List<Recorder.StackSample> samples = MockProfileObjects.getPredefinedStackSamples(1);
    Recorder.StackSampleWse ssw1 = Recorder.StackSampleWse.newBuilder()
        .addStackSample(samples.get(0))
        .build();
    Recorder.StackSampleWse ssw2 = Recorder.StackSampleWse.newBuilder()
        .addStackSample(samples.get(1))
        .build();
    Recorder.StackSampleWse ssw3 = Recorder.StackSampleWse.newBuilder()
        .addStackSample(samples.get(2))
        .build();

    Recorder.Wse wse1 = MockProfileObjects.getMockCpuWseWithStackSample(ssw1, null);
    Recorder.Wse wse2 = MockProfileObjects.getMockCpuWseWithStackSample(ssw2, null);
    Recorder.Wse wse3 = MockProfileObjects.getMockCpuWseWithStackSample(ssw3, null);

    return Arrays.asList(wse1, wse2, wse3);
  }

  public enum HeaderPayloadStrategy {
    VALID,
    INVALID_CHECKSUM,
    INVALID_RECORDING_HEADER,
    INVALID_HEADER_LENGTH,
    INVALID_WORK_ID
  }

  public enum WsePayloadStrategy {
    VALID,
    INVALID_CHECKSUM,
    INVALID_WSE,
    INVALID_WSE_LENGTH
  }

  public static class ResponsePayload {
    public Buffer buffer;
    public int statusCode;

    public ResponsePayload(int statusCode, Buffer buffer) {
      this.statusCode = statusCode;
      this.buffer = buffer;
    }
  }

}
