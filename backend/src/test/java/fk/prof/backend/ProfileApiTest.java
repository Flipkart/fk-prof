package fk.prof.backend;

import com.google.protobuf.CodedOutputStream;
import fk.prof.aggregation.model.*;
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
import fk.prof.idl.Profile;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;
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
    Profile.RecordingPolicy policy = buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60);
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, policy));

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
        Map<WorkEntities.WorkType, Integer> expectedSamplesMap = new HashMap<>();
        expectedSamplesMap.put(WorkEntities.WorkType.cpu_sample_work, 3);
        FinalizedProfileWorkInfo expectedWorkInfo = getExpectedWorkInfo(actual.getDetailsForWorkId(workId).getStartedAt(),
            actual.getDetailsForWorkId(workId).getEndedAt(), expectedSamplesMap);
        Map<Long, FinalizedProfileWorkInfo> expectedWorkLookup = new HashMap<>();
        expectedWorkLookup.put(workId, expectedWorkInfo);
        Map<WorkEntities.WorkType, FinalizedWorkSpecificAggregationBucket> expectedWorkSpecificBuckets = new HashMap<>();
        expectedWorkSpecificBuckets.put(WorkEntities.WorkType.cpu_sample_work, expectedAggregationBucket);
        FinalizedAggregationWindow expected = new FinalizedAggregationWindow("a", "c", "p",
            awStart, null, 30 * 60,
            expectedWorkLookup, policy, expectedWorkSpecificBuckets);
        context.assertTrue(expected.equals(actual));
        async.complete();
      }
    });
  }

  @Test(timeout = 5000)
  public void testWithValidSingleProfile_AllWorkTypes(TestContext context) {
    long workId = workIdCounter.incrementAndGet();
    Profile.RecordingPolicy policy = buildRecordingPolicy(new HashSet<>(Arrays.asList(
            WorkEntities.WorkType.cpu_sample_work, WorkEntities.WorkType.io_trace_work)),
            60);
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
            new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId}, policy));

    final Async async = context.async();
    Future<ResponsePayload> future = makeValidProfileRequest(
            MockProfileObjects.getRecordingHeader(workId, MockProfileObjects.wType_all),
            getMockWseEntriesForSingleProfile(MockProfileObjects.wType_all));

    future.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        context.assertEquals(200, ar.result().statusCode);
        //Validate aggregation
        AggregationWindow aggregationWindow = activeAggregationWindows.getAssociatedAggregationWindow(workId);
        FinalizedAggregationWindow actual = aggregationWindow.finalizeEntity();

        FinalizedCpuSamplingAggregationBucket expectedAggregationBucket = getExpectedAggregationBucketOfPredefinedSamples();
        FinalizedIOTracingAggregationBucket expectedIOTracingBucket = getExpectedAggregationBucketForIOTracing();

        Map<WorkEntities.WorkType, Integer> expectedSamplesMap = new HashMap<>();
        expectedSamplesMap.put(WorkEntities.WorkType.cpu_sample_work, 3);
        expectedSamplesMap.put(WorkEntities.WorkType.io_trace_work, 4);

        FinalizedProfileWorkInfo expectedWorkInfo = getExpectedWorkInfo(actual.getDetailsForWorkId(workId).getStartedAt(),
                actual.getDetailsForWorkId(workId).getEndedAt(), expectedSamplesMap);

        Map<Long, FinalizedProfileWorkInfo> expectedWorkLookup = new HashMap<>();
        expectedWorkLookup.put(workId, expectedWorkInfo);

        Map<WorkEntities.WorkType, FinalizedWorkSpecificAggregationBucket> expectedWorkSpecificBuckets = new HashMap<>();
        expectedWorkSpecificBuckets.put(WorkEntities.WorkType.cpu_sample_work, expectedAggregationBucket);
        expectedWorkSpecificBuckets.put(WorkEntities.WorkType.io_trace_work, expectedIOTracingBucket);

        FinalizedAggregationWindow expected = new FinalizedAggregationWindow("a", "c", "p",
                awStart, null, 30 * 60,
                expectedWorkLookup, policy, expectedWorkSpecificBuckets);
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
    Profile.RecordingPolicy policy = buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60);
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    AggregationWindow aw = new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId1, workId2, workId3}, policy);
    activeAggregationWindows.associateAggregationWindow(new long[] {workId1, workId2, workId3}, aw);
    List<Recording.RecordingChunk> recList = getMockChunksForMultipleProfiles();

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId1), Arrays.asList(recList.get(0)));
    Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId2), Arrays.asList(recList.get(1)));
    Future<ResponsePayload> f3 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId3), Arrays.asList(recList.get(2)));
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

        Map<WorkEntities.WorkType, Integer> expectedSamplesMap1 = new HashMap<>();
        expectedSamplesMap1.put(WorkEntities.WorkType.cpu_sample_work, 1);
        FinalizedProfileWorkInfo expectedWorkInfo1 = getExpectedWorkInfo(actual.getDetailsForWorkId(workId1).getStartedAt(),
            actual.getDetailsForWorkId(workId1).getEndedAt(), expectedSamplesMap1);

        Map<WorkEntities.WorkType, Integer> expectedSamplesMap2 = new HashMap<>();
        expectedSamplesMap2.put(WorkEntities.WorkType.cpu_sample_work, 1);
        FinalizedProfileWorkInfo expectedWorkInfo2 = getExpectedWorkInfo(actual.getDetailsForWorkId(workId2).getStartedAt(),
            actual.getDetailsForWorkId(workId2).getEndedAt(), expectedSamplesMap2);

        Map<WorkEntities.WorkType, Integer> expectedSamplesMap3 = new HashMap<>();
        expectedSamplesMap3.put(WorkEntities.WorkType.cpu_sample_work, 1);
        FinalizedProfileWorkInfo expectedWorkInfo3 = getExpectedWorkInfo(actual.getDetailsForWorkId(workId3).getStartedAt(),
            actual.getDetailsForWorkId(workId3).getEndedAt(), expectedSamplesMap3);

        Map<Long, FinalizedProfileWorkInfo> expectedWorkLookup = new HashMap<>();
        expectedWorkLookup.put(workId1, expectedWorkInfo1);
        expectedWorkLookup.put(workId2, expectedWorkInfo2);
        expectedWorkLookup.put(workId3, expectedWorkInfo3);

        Map<WorkEntities.WorkType, FinalizedWorkSpecificAggregationBucket> expectedWorkSpecificBuckets = new HashMap<>();
        expectedWorkSpecificBuckets.put(WorkEntities.WorkType.cpu_sample_work, expectedAggregationBucket);
        FinalizedAggregationWindow expected = new FinalizedAggregationWindow("a", "c", "p",
            awStart, null, 30 * 60,
            expectedWorkLookup, policy, expectedWorkSpecificBuckets);

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
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId},
            buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60)));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
        HeaderPayloadStrategy.VALID, ChunkPayloadStrategy.VALID, true, 0);
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
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId},
            buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60)));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
        HeaderPayloadStrategy.VALID, ChunkPayloadStrategy.VALID, true, 10000);
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
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId},
            buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60)));

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
        HeaderPayloadStrategy.VALID, ChunkPayloadStrategy.INVALID_CHECKSUM, false, 0);
    f1.setHandler(ar -> {
      if (ar.failed()) {
        context.fail(ar.cause());
      } else {
        context.assertEquals(400, f1.result().statusCode);
        Future<ResponsePayload> f2 = makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), getMockWseEntriesForSingleProfile(),
            HeaderPayloadStrategy.VALID, ChunkPayloadStrategy.VALID, false, 0);
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
    AggregationWindow aw = new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId1, workId2},
        buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60));
    activeAggregationWindows.associateAggregationWindow(new long[] {workId1}, aw);
    List<Recording.RecordingChunk> recList = getMockChunksForMultipleProfiles();

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId1, 1), Arrays.asList(recList.get(0)));
    Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId2, 2), Arrays.asList(recList.get(1)));
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
        new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId},
            buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60)));

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
    makeInvalidChunkProfileRequest(context, ChunkPayloadStrategy.INVALID_CHUNK_LENGTH, "invalid length for recordingchunk");
  }

  @Test(timeout = 5000)
  public void testWithInvalidWse(TestContext context) {
    makeInvalidChunkProfileRequest(context, ChunkPayloadStrategy.INVALID_CHUNK, "error while parsing recordingchunk");
  }

  @Test(timeout = 5000)
  public void testWithInvalidWseChecksum(TestContext context) {
    makeInvalidChunkProfileRequest(context, ChunkPayloadStrategy.INVALID_CHECKSUM, "checksum of recording chunk does not match");
  }

  @Test(timeout = 5000)
  public void testAggregationWindowExpiryWhileReportingProfiles(TestContext context) throws InterruptedException {
    long workId1 = workIdCounter.incrementAndGet();
    long workId2 = workIdCounter.incrementAndGet();
    long workId3 = workIdCounter.incrementAndGet();
    LocalDateTime awStart = LocalDateTime.now(Clock.systemUTC());
    AggregationWindow aw = new AggregationWindow("a", "c", "p", awStart, 30 * 60, new long[]{workId1, workId2, workId3},
        buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60));
    activeAggregationWindows.associateAggregationWindow(new long[] {workId1, workId2, workId3}, aw);
    List<Recording.RecordingChunk> recList = getMockChunksForMultipleProfiles();

    final Async async = context.async();
    Future<ResponsePayload> f1 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId1), Arrays.asList(recList.get(0)));
    //make a long running profile request
    Future<ResponsePayload> f2 = makeValidProfileRequest(MockProfileObjects.getRecordingHeader(workId2), Arrays.asList(recList.get(1)), 2000);
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

  private Future<ResponsePayload> makeValidProfileRequest(Recording.RecordingHeader recordingHeader, List<Recording.RecordingChunk> recList) {
    return makeValidProfileRequest(recordingHeader, recList, 0);
  }

  private Future<ResponsePayload> makeValidProfileRequest(Recording.RecordingHeader recordingHeader, List<Recording.RecordingChunk> recList, int additionalDelayInMs) {
    return makeProfileRequest(vertx, port, recordingHeader, recList, HeaderPayloadStrategy.VALID, ChunkPayloadStrategy.VALID, false, additionalDelayInMs);
  }

  private void makeInvalidHeaderProfileRequest(TestContext context, HeaderPayloadStrategy payloadStrategy, String errorToGrep) {
    long workId = workIdCounter.incrementAndGet();
    if (!payloadStrategy.equals(HeaderPayloadStrategy.INVALID_WORK_ID)) {
      activeAggregationWindows.associateAggregationWindow(new long[] {workId},
          new AggregationWindow("a", "c", "p", LocalDateTime.now(), 30 * 60, new long[]{workId},
              buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60)));
    }
    final Async async = context.async();

    makeProfileRequest(vertx, port, MockProfileObjects.getRecordingHeader(workId), null, payloadStrategy, ChunkPayloadStrategy.VALID, false, 0)
        .setHandler(ar -> {
          if(ar.failed()) {
            context.fail(ar.cause());
          }
          context.assertEquals(400, ar.result().statusCode);
          context.assertTrue(ar.result().buffer.toString().toLowerCase().contains(errorToGrep));
          async.complete();
        });
  }

  private void makeInvalidChunkProfileRequest(TestContext context, ChunkPayloadStrategy payloadStrategy, String errorToGrep) {
    makeInvalidChunkProfileRequest(context, payloadStrategy, errorToGrep, false);
  }

  private void makeInvalidChunkProfileRequest(TestContext context, ChunkPayloadStrategy payloadStrategy, String errorToGrep, boolean skipEndMarker) {
    long workId = workIdCounter.incrementAndGet();
    activeAggregationWindows.associateAggregationWindow(new long[] {workId},
        new AggregationWindow("a", "c", "p", LocalDateTime.now(), 30 * 60, new long[]{workId},
            buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)), 60)));
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
      expectedTraceDetail.getGlobalRoot().incrementOnStackSamples();
      expectedRoot.incrementOnStackSamples();
      expectedTraceDetail.incrementSamples();
    }
    expectedTraceDetailLookup.put("1", expectedTraceDetail);

    FinalizedCpuSamplingAggregationBucket expected = new FinalizedCpuSamplingAggregationBucket(
        expectedMethodIdLookup, expectedTraceDetailLookup
    );

    return expected;
  }

  private FinalizedIOTracingAggregationBucket getExpectedAggregationBucketForIOTracing() {
    MethodIdLookup expectedMethodIdLookup = new MethodIdLookup();
    expectedMethodIdLookup.getOrAdd("#D ()");
    expectedMethodIdLookup.getOrAdd("#B ()");
    expectedMethodIdLookup.getOrAdd("#A ()");
    expectedMethodIdLookup.getOrAdd("#F ()");
    expectedMethodIdLookup.getOrAdd("#E ()");

    Map<String, IOTracingTraceDetail> traces = new HashMap<>();

    IOTracingTraceDetail expectedDetails = new IOTracingTraceDetail();

    IOTracingFrameNode expectedRoot = expectedDetails.getUnclassifiableRoot();
    IOTracingFrameNode d1 = expectedRoot.getOrAddChild(2, 10);
    IOTracingFrameNode b1 = d1.getOrAddChild(3, 10);
    IOTracingFrameNode a1 = b1.getOrAddChild(4, 10);
    a1.addTrace(10, Recording.IOTraceType.file_read, 1000, 1000, false);

    IOTracingFrameNode f1 = expectedRoot.getOrAddChild(5, 10);
    IOTracingFrameNode e1 = f1.getOrAddChild(6, 10);
    IOTracingFrameNode b2 = e1.getOrAddChild(3, 10);
    IOTracingFrameNode a2 = b2.getOrAddChild(4, 10);
    a2.addTrace(11, Recording.IOTraceType.socket_read, 1000, 1100, false);

    IOTracingFrameNode e2 = expectedRoot.getOrAddChild(6, 10);
    IOTracingFrameNode b3 = e2.getOrAddChild(3, 10);
    IOTracingFrameNode a3 = b3.getOrAddChild(4, 10);
    a3.addTrace(10, Recording.IOTraceType.file_write, 1000, 1000, false);

    IOTracingFrameNode e3 = e2.getOrAddChild(6, 10);
    IOTracingFrameNode b4 = e3.getOrAddChild(3, 10);
    IOTracingFrameNode a4 = b4.getOrAddChild(4, 10);
    a4.addTrace(11, Recording.IOTraceType.socket_write, 1000, 1100, false);

    for (int i = 0; i < 4; i++) {
      expectedDetails.incrementSamples();
    }
    traces.put("1", expectedDetails);

    return new FinalizedIOTracingAggregationBucket(expectedMethodIdLookup, traces);
  }

  private FinalizedProfileWorkInfo getExpectedWorkInfo(LocalDateTime startedAt, LocalDateTime endedAt, Map<WorkEntities.WorkType, Integer> samplesMap) {
    Map<String, Integer> expectedTraceCoverages = new HashMap<>();
    expectedTraceCoverages.put("1", 5);
    FinalizedProfileWorkInfo expectedProfileWorkInfo = new FinalizedProfileWorkInfo(1, null, AggregationState.COMPLETED,
        startedAt, endedAt, 60, expectedTraceCoverages, samplesMap);
    return expectedProfileWorkInfo;
  }

  private Profile.RecordingPolicy buildRecordingPolicy(Set<WorkEntities.WorkType> workTypes, int recDuration) {
    List<WorkEntities.Work> workList = new ArrayList<>();
    for(WorkEntities.WorkType workType: workTypes) {
      workList.add(WorkEntities.Work.newBuilder().setWType(workType).build());
    }
    return Profile.RecordingPolicy.newBuilder().setDuration(recDuration).setMinHealthy(10)
        .setDescription("Test policy").setCoveragePct(100).addAllWork(workList).build();
  }


  public static Future<ResponsePayload> makeProfileRequest(Vertx vertx, int port, Recording.RecordingHeader recordingHeader, List<Recording.RecordingChunk> recList, HeaderPayloadStrategy headerPayloadStrategy, ChunkPayloadStrategy chunkPayloadStrategy, boolean skipEndMarker, int additionalDelayInMs) {
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
        if(recList != null) {
          writeMockChunkEntriesToRequest(recList, requestStream, chunkPayloadStrategy);
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

  public static void writeMockHeaderToRequest(Recording.RecordingHeader recordingHeader, ByteArrayOutputStream requestStream) throws IOException {
    writeMockHeaderToRequest(recordingHeader, requestStream, HeaderPayloadStrategy.VALID);
  }

  public static void writeMockHeaderToRequest(Recording.RecordingHeader recordingHeader, ByteArrayOutputStream requestStream, HeaderPayloadStrategy payloadStrategy) throws IOException {
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

  public static void writeMockChunkEntriesToRequest(List<Recording.RecordingChunk> recList, ByteArrayOutputStream requestStream) throws IOException {
    writeMockChunkEntriesToRequest(recList, requestStream, ChunkPayloadStrategy.VALID);
  }

  public static void writeMockChunkEntriesToRequest(List<Recording.RecordingChunk> recList, ByteArrayOutputStream requestStream, ChunkPayloadStrategy payloadStrategy) throws IOException {
    if (recList != null) {
      for (Recording.RecordingChunk rec : recList) {
        writeChunkToRequest(rec, requestStream, payloadStrategy);
      }
    }
  }

  public static void writeChunkToRequest(Recording.RecordingChunk rec, ByteArrayOutputStream requestStream, ChunkPayloadStrategy payloadStrategy) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);
    byte[] chunkBytes = rec.toByteArray();

    if (payloadStrategy.equals(ChunkPayloadStrategy.INVALID_CHUNK_LENGTH)) {
      codedOutputStream.writeUInt32NoTag(Integer.MAX_VALUE);
    } else {
      codedOutputStream.writeUInt32NoTag(chunkBytes.length);
    }

    if (payloadStrategy.equals(ChunkPayloadStrategy.INVALID_CHUNK)) {
      byte[] invalidArr = Arrays.copyOfRange(chunkBytes, 0, chunkBytes.length);
      invalidArr[0] = invalidArr[1] = invalidArr[3] = 0;
      invalidArr[invalidArr.length - 1] = invalidArr[invalidArr.length - 2] = invalidArr[invalidArr.length - 3] = 0;
      codedOutputStream.writeByteArrayNoTag(invalidArr);
    } else {
      rec.writeTo(codedOutputStream);
    }
    codedOutputStream.flush();
    byte[] bytesWritten = outputStream.toByteArray();

    Checksum checksum = new Adler32();
    checksum.update(bytesWritten, 0, bytesWritten.length);
    long checksumValue = payloadStrategy.equals(ChunkPayloadStrategy.INVALID_CHECKSUM) ? 0 : checksum.getValue();
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

  private static List<Recording.StackSampleWse> getMockCpuStackSampleWse() {
    List<Recording.StackSample> samples = MockProfileObjects.getPredefinedStackSamples(1);
    Recording.StackSampleWse ssw1 = Recording.StackSampleWse.newBuilder()
            .addStackSample(samples.get(0))
            .addStackSample(samples.get(1))
            .build();
    Recording.StackSampleWse ssw2 = Recording.StackSampleWse.newBuilder()
            .addStackSample(samples.get(2))
            .build();
    return Arrays.asList(ssw1, ssw2);
  }

  private static List<Recording.IOTraceWse> getMockIOTraceStackSampleWse() {
    List<Recording.IOTrace> traces = MockProfileObjects.getPredefinedIOTraces(1);

    return Arrays.asList(
        Recording.IOTraceWse.newBuilder()
            .addTraces(traces.get(0))
            .addTraces(traces.get(1)).build(),
        Recording.IOTraceWse.newBuilder()
            .addTraces(traces.get(2))
            .addTraces(traces.get(3)).build()
    );
  }

  private static Map<WorkEntities.WorkType, List> sampleWse = new HashMap<WorkEntities.WorkType, List>() {{
    put(WorkEntities.WorkType.cpu_sample_work, getMockCpuStackSampleWse());
    put(WorkEntities.WorkType.io_trace_work, getMockIOTraceStackSampleWse());
  }};

  public static List<Recording.RecordingChunk> getMockWseEntriesForSingleProfile(Set<WorkEntities.WorkType> workTypes) {
    Map<WorkEntities.WorkType, List> wses = new HashMap<>();
    for(WorkEntities.WorkType wt: workTypes) {
      wses.put(wt, sampleWse.get(wt));
    }

    return MockProfileObjects.getMockRecordingChunks(wses);
  }

  public static List<Recording.RecordingChunk> getMockWseEntriesForSingleProfile() {
    return getMockWseEntriesForSingleProfile(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work)));
  }

  public static List<Recording.RecordingChunk> getMockChunksForMultipleProfiles() {
    List<Recording.StackSample> samples = MockProfileObjects.getPredefinedStackSamples(1);
    Recording.StackSampleWse ssw1 = Recording.StackSampleWse.newBuilder()
        .addStackSample(samples.get(0))
        .build();
    Recording.StackSampleWse ssw2 = Recording.StackSampleWse.newBuilder()
        .addStackSample(samples.get(1))
        .build();
    Recording.StackSampleWse ssw3 = Recording.StackSampleWse.newBuilder()
        .addStackSample(samples.get(2))
        .build();

    Map<WorkEntities.WorkType, List> wses = new HashMap<>();

    wses.put(WorkEntities.WorkType.cpu_sample_work, Arrays.asList(ssw1));
    Recording.RecordingChunk rec1 = MockProfileObjects.getMockRecordingChunks(wses).get(0);

    wses.put(WorkEntities.WorkType.cpu_sample_work, Arrays.asList(ssw2));
    Recording.RecordingChunk rec2 = MockProfileObjects.getMockRecordingChunks(wses).get(0);

    wses.put(WorkEntities.WorkType.cpu_sample_work, Arrays.asList(ssw3));
    Recording.RecordingChunk rec3 = MockProfileObjects.getMockRecordingChunks(wses).get(0);

    return Arrays.asList(rec1, rec2, rec3);
  }

  public enum HeaderPayloadStrategy {
    VALID,
    INVALID_CHECKSUM,
    INVALID_RECORDING_HEADER,
    INVALID_HEADER_LENGTH,
    INVALID_WORK_ID
  }

  public enum ChunkPayloadStrategy {
    VALID,
    INVALID_CHECKSUM,
    INVALID_CHUNK,
    INVALID_CHUNK_LENGTH
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
