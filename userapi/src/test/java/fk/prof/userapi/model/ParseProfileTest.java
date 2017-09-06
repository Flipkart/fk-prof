package fk.prof.userapi.model;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowSerializer;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.serialize.Serializer;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.ObjectNotFoundException;
import fk.prof.storage.S3AsyncStorage;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.Deserializer;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPI;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.model.json.ProtoSerializers;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.mockito.Mockito.*;

/**
 * @author gaurav.ashok
 */
@RunWith(VertxUnitRunner.class)
public class ParseProfileTest {

    ProfileStoreAPI profileDiscoveryAPI;
    AsyncStorage asyncStorage;
    Vertx vertx;

    final String traceName1 = "print-trace-1";
    final String traceName2 = "doSome-trace-2";
    private Configuration config;

    @Test
    public void testReadWriteForVariant() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Serializer.writeVariantInt32(Integer.MAX_VALUE + 10000, out);
        int read = Deserializer.readVariantInt32(new ByteArrayInputStream(out.toByteArray()));
        assert read == Integer.MAX_VALUE + 10000;
    }

    @BeforeClass
    public static void setup() {
        ProtoSerializers.registerSerializers(Json.mapper);
    }

    @Before
    public void testSetUp(TestContext context) throws Exception{
        vertx = Vertx.vertx();
        asyncStorage = mock(AsyncStorage.class);
        config = UserapiConfigManager.loadConfig(ParseProfileTest.class.getClassLoader().getResource("userapi-conf.json").getFile());
        profileDiscoveryAPI = new ProfileStoreAPIImpl(vertx, asyncStorage, 30, config.getProfileLoadTimeout(), config.getVertxWorkerPoolSize());
    }

    @After
    public void tearDown(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }

    @Test(timeout = 10000)
    public void testAggregatedProfileStoreS3Impl(TestContext context) throws Exception {
        Async async = context.async();

        S3AsyncStorage storage = mock(S3AsyncStorage.class);
        String fileName = AggregatedProfileNamingStrategy.fromHeader("profiles", buildHeader()).getFileName(0);
        InputStream s3InputStream = buildDefaultS3DataStream();

        // for above filename return the inputStream
        when(storage.fetchAsync(fileName)).thenReturn(CompletableFuture.supplyAsync(() -> s3InputStream));
        // for other filenames throw ObjectNotFoundException
        when(storage.fetchAsync(argThat(arg -> !fileName.equals(arg)))).thenReturn(CompletableFuture.supplyAsync(() -> {
            throw new ObjectNotFoundException("not found");
        }));

        profileDiscoveryAPI = new ProfileStoreAPIImpl(vertx, storage, 30, config.getProfileLoadTimeout(), config.getVertxWorkerPoolSize());

        Future<AggregatedProfileInfo> future1 = Future.future();
        Future<AggregatedProfileInfo> future2 = Future.future();

        CompositeFuture cfuture = CompositeFuture.all(future1, future2);
        cfuture.setHandler(result -> {
            try {
                if (result.failed()) {
                    context.fail(result.cause());
                } else {
                    // match the response
                    AggregatedProfileInfo firstResult = result.result().resultAt(0);
                    AggregatedProfileInfo secondResult = result.result().resultAt(1);
                    testEquality(context, buildDefaultProfileInfo(), firstResult);
                    // both results are actually the same cached object
                    context.assertTrue(firstResult == secondResult);

                    // gzip buffer size is 512 and our content size is ~470 bytes, so fetchAsync will be called 2 times.
                    verify(storage, times(2)).fetchAsync(any());
                    verifyNoMoreInteractions(storage);
                }
            }
            catch (Exception e) {
                context.fail(e);
            }
            finally {
                async.complete();
            }
        });

        profileDiscoveryAPI.load(future1, AggregatedProfileNamingStrategy.fromHeader("profiles", buildHeader()));
        profileDiscoveryAPI.load(future2, AggregatedProfileNamingStrategy.fromHeader("profiles", buildHeader()));
    }

    private void testEquality(TestContext context, AggregatedProfileInfo expected, AggregatedProfileInfo actual) {
        context.assertEquals(expected.getStart(), actual.getStart());
        context.assertEquals(expected.getDuration(), actual.getDuration());
        testListEquality(context, expected.getTraces(), actual.getTraces(), "traces");
        testListEquality(context, expected.getTraceDetails(), actual.getTraceDetails(), "traceDetails");
        testListEquality(context, expected.getProfiles(), actual.getProfiles(), "profile work info");
        context.assertEquals(expected.getAggregatedSamples(traceName1).getMethodLookup(), actual.getAggregatedSamples(traceName1).getMethodLookup());
        context.assertEquals(expected.getAggregatedSamples(traceName2).getMethodLookup(), actual.getAggregatedSamples(traceName2).getMethodLookup());

        if(expected.getAggregatedSamples(traceName1).getAggregatedSamples() instanceof AggregatedCpuSamplesData) {
            testEquality(context, (AggregatedCpuSamplesData)expected.getAggregatedSamples(traceName1).getAggregatedSamples(),
                    (AggregatedCpuSamplesData)actual.getAggregatedSamples(traceName1).getAggregatedSamples());

            testEquality(context, (AggregatedCpuSamplesData)expected.getAggregatedSamples(traceName2).getAggregatedSamples(),
                    (AggregatedCpuSamplesData)actual.getAggregatedSamples(traceName2).getAggregatedSamples());
        }
        else {
            context.fail("Unexpected type of AggregatedSamples in profileInfo");
        }
    }

    private void testEquality(TestContext context, AggregatedCpuSamplesData expected, AggregatedCpuSamplesData actual) {
        Iterator<AggregatedProfileModel.FrameNode> expectedFN = expected.getFrameNodes().iterator();
        Iterator<AggregatedProfileModel.FrameNode> actualFN = actual.getFrameNodes().iterator();

        while(expectedFN.hasNext() && actualFN.hasNext()) {
            context.assertEquals(expectedFN.next(), actualFN.next());
        }

        if(expectedFN.hasNext() && !actualFN.hasNext()) {
            context.fail("expected more FrameNodes in the actual list");
        }
        else if(!expectedFN.hasNext() && actualFN.hasNext()) {
            context.fail("found more frameNodes than expected in the actual list");
        }
    }

    private <T> void testListEquality(TestContext context, Iterable<T> expected, Iterable<T> actual, String tag) {
        Iterator<T> expectedIt = expected.iterator();
        Iterator<T> actualIt = actual.iterator();

        while (expectedIt.hasNext() && actualIt.hasNext()) {
            context.assertEquals(expectedIt.next(), actualIt.next());
        }

        if(expectedIt.hasNext()) {
            context.fail("expected more elements in " + tag);
        }

        if(actualIt.hasNext()) {
            context.fail("unexpected elements found in " + tag);
        }
    }

    private AggregatedProfileInfo buildDefaultProfileInfo() {
        List<AggregatedProfileModel.FrameNodeList> frameNodes = buildFrameNodes();
        Map<String, AggregatedSamplesPerTraceCtx> samples = new HashMap<>();
        // first 2 elements belong to trace1
        samples.put(traceName1, new AggregatedSamplesPerTraceCtx(buildMethodLookup(), new AggregatedCpuSamplesData(new StacktraceTreeIterable(frameNodes.subList(0,2)))));
        // next 2 elements belong to trace 2
        samples.put(traceName2, new AggregatedSamplesPerTraceCtx(buildMethodLookup(), new AggregatedCpuSamplesData(new StacktraceTreeIterable(frameNodes.subList(2,4)))));

        return new AggregatedProfileInfo(buildHeader(), buildTraceName(traceName1, traceName2), buildTraceCtxList(), buildProfilesSummary(), samples);
    }

    private InputStream buildDefaultS3DataStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Adler32 adler32 = new Adler32();

        OutputStream zout = new GZIPOutputStream(out);
        CheckedOutputStream cout = new CheckedOutputStream(zout, adler32);

        Serializer.writeVariantInt32(AggregationWindowSerializer.AGGREGATION_FILE_MAGIC_NUM, cout);

        adler32.reset();
        buildHeader().writeDelimitedTo(cout);
        Serializer.writeVariantInt32((int)adler32.getValue(), cout);

        // traces
        adler32.reset();
        buildTraceName(traceName1, traceName2).writeDelimitedTo(cout);
        Serializer.writeVariantInt32((int)adler32.getValue(), cout);

        adler32.reset();
        buildTraceCtxList().writeDelimitedTo(cout);
        Serializer.writeVariantInt32((int)adler32.getValue(), cout);

        // profile info
        adler32.reset();
        for(AggregatedProfileModel.ProfileWorkInfo workInfo: buildProfilesSummary()) {
            workInfo.writeDelimitedTo(cout);
        }
        Serializer.writeVariantInt32(0, cout);
        Serializer.writeVariantInt32((int)adler32.getValue(), cout);

        // cpu sample
        adler32.reset();
        buildMethodLookup().writeDelimitedTo(cout);
        Serializer.writeVariantInt32((int)adler32.getValue(), cout);

        adler32.reset();
        for (AggregatedProfileModel.FrameNodeList frameNodes : buildFrameNodes()) {
            frameNodes.writeDelimitedTo(cout);
        }
        Serializer.writeVariantInt32((int)adler32.getValue(), cout);

        cout.flush();
        cout.close();

        byte[] bytes = out.toByteArray();
        System.out.println("Size of zipped data: " + bytes.length);
        return new ByteArrayInputStream(bytes);
    }

    private AggregatedProfileModel.MethodLookUp buildMethodLookup() {
        return AggregatedProfileModel.MethodLookUp.newBuilder()
                .addFqdn("~ ROOT ~.()")
                .addFqdn("~ UNCLASSIFIABLE ~.()")
                .addFqdn("com.example.App.main(String[])")
                .addFqdn("com.example.App.print(String)")
                .addFqdn("com.example.App.doSomething(String, int)")
                .build();
    }

    /**
     * @return Returns a List of FrameNodes for a stackTrace tree:
     *         root
     *         |_ unclassified
     *         |_ main
     *            |_ dosomething
     *            |_ print
     */
    private List<AggregatedProfileModel.FrameNodeList> buildFrameNodes() {
        List<AggregatedProfileModel.FrameNodeList> list = new ArrayList<>();

        list.add(AggregatedProfileModel.FrameNodeList.newBuilder()
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(0).setLineNo(0).setChildCount(2).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(600).setOnCpuSamples(0)))
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(1).setLineNo(0).setChildCount(0).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(0).setOnCpuSamples(0)))
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(2).setLineNo(10).setChildCount(1).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(600).setOnCpuSamples(0)))
                .setTraceCtxIdx(0)
                .build());

        list.add(AggregatedProfileModel.FrameNodeList.newBuilder()
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(4).setLineNo(20).setChildCount(1).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(600).setOnCpuSamples(0)))
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(3).setLineNo(40).setChildCount(0).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(600).setOnCpuSamples(600)))
                .setTraceCtxIdx(0)
                .build());

        list.add(AggregatedProfileModel.FrameNodeList.newBuilder()
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(0).setLineNo(0).setChildCount(2).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(1280).setOnCpuSamples(0)))
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(1).setLineNo(0).setChildCount(0).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(0).setOnCpuSamples(0)))
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(2).setLineNo(10).setChildCount(1).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(1280).setOnCpuSamples(0)))
                .setTraceCtxIdx(1)
                .build());

        list.add(AggregatedProfileModel.FrameNodeList.newBuilder()
                .addFrameNodes(AggregatedProfileModel.FrameNode.newBuilder().setMethodId(4).setLineNo(21).setChildCount(0).setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder().setOnStackSamples(1280).setOnCpuSamples(1280)))
                .setTraceCtxIdx(1)
                .build());

        return list;
    }

    public static AggregatedProfileModel.Header buildHeader() {
        ZonedDateTime start = ZonedDateTime.parse("2017-01-30T09:54:53.852Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return AggregatedProfileModel.Header.newBuilder()
                .setAppId("app1")
                .setProcId("svc1")
                .setClusterId("cluster1")
                .setWorkType(AggregatedProfileModel.WorkType.cpu_sample_work)
                .setAggregationStartTime(start.format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
                .setAggregationEndTime(start.plusMinutes(30).format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
                .setFormatVersion(1)
                .build();
    }

    public static AggregatedProfileModel.TraceCtxNames buildTraceName(String... traces) {
        return AggregatedProfileModel.TraceCtxNames.newBuilder().addAllName(Arrays.asList(traces)).build();
    }

    public static AggregatedProfileModel.TraceCtxDetailList buildTraceCtxList() {
        return AggregatedProfileModel.TraceCtxDetailList.newBuilder()
                .addTraceCtx(AggregatedProfileModel.TraceCtxDetail.newBuilder()
                        .setTraceIdx(0)
                        .setSampleCount(600))
                .addTraceCtx(AggregatedProfileModel.TraceCtxDetail.newBuilder()
                        .setTraceIdx(1)
                        .setSampleCount(1280)).build();
    }

    public static AggregatedProfileModel.RecorderInfo[] buildRecordersList() {
        return new AggregatedProfileModel.RecorderInfo[] {
            AggregatedProfileModel.RecorderInfo.newBuilder()
                .setIp("192.168.1.1")
                .setHostname("some-box-1")
                .setAppId("app1")
                .setInstanceGroup("ig1")
                .setCluster("cluster1")
                .setInstanceId("instance1")
                .setProcessName("svc1")
                .setVmId("vm1")
                .setZone("chennai-1")
                .setInstanceType("c1.xlarge").build(),
            AggregatedProfileModel.RecorderInfo.newBuilder()
                .setIp("192.168.1.2")
                .setHostname("some-box-2")
                .setAppId("app1")
                .setInstanceGroup("ig1")
                .setCluster("cluster1")
                .setInstanceId("instance2")
                .setProcessName("svc1")
                .setVmId("vm2")
                .setZone("chennai-1")
                .setInstanceType("c1.xlarge").build()};
    }

    public static List<AggregatedProfileModel.ProfileWorkInfo> buildProfilesSummary() {
        List<AggregatedProfileModel.ProfileWorkInfo> workInfos = new ArrayList<>();
        AggregatedProfileModel.RecorderInfo[] recorders = buildRecordersList();

        workInfos.add(AggregatedProfileModel.ProfileWorkInfo.newBuilder()
                .setStartOffset(10)
                .setDuration(60)
                .setRecorderVersion(1)
                .setRecorderInfo(recorders[0])
                .addSampleCount(AggregatedProfileModel.ProfileWorkInfo.SampleCount.newBuilder().setWorkType(AggregatedProfileModel.WorkType.cpu_sample_work).setSampleCount(900))
                .setStatus(AggregatedProfileModel.AggregationStatus.Completed)
                .addTraceCoverageMap(AggregatedProfileModel.ProfileWorkInfo.TraceCtxToCoveragePctMap.newBuilder()
                        .setTraceCtxIdx(0)
                        .setCoveragePct(5))
                .addTraceCoverageMap(AggregatedProfileModel.ProfileWorkInfo.TraceCtxToCoveragePctMap.newBuilder()
                        .setTraceCtxIdx(1)
                        .setCoveragePct(10)).build());

        workInfos.add(AggregatedProfileModel.ProfileWorkInfo.newBuilder()
                .setStartOffset(24)
                .setDuration(60)
                .setRecorderVersion(1)
                .setRecorderInfo(recorders[1])
                .addSampleCount(AggregatedProfileModel.ProfileWorkInfo.SampleCount.newBuilder().setWorkType(AggregatedProfileModel.WorkType.cpu_sample_work).setSampleCount(980))
                .setStatus(AggregatedProfileModel.AggregationStatus.Retried)
                .addTraceCoverageMap(AggregatedProfileModel.ProfileWorkInfo.TraceCtxToCoveragePctMap.newBuilder()
                        .setTraceCtxIdx(0)
                        .setCoveragePct(5))
                .addTraceCoverageMap(AggregatedProfileModel.ProfileWorkInfo.TraceCtxToCoveragePctMap.newBuilder()
                        .setTraceCtxIdx(1)
                        .setCoveragePct(10)).build());

        return workInfos;
    }
}
