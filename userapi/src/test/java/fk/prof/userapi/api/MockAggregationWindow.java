package fk.prof.userapi.api;

import com.codahale.metrics.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedOutputStream;
import fk.prof.aggregation.model.*;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.ObjectNotFoundException;
import fk.prof.storage.buffer.ByteBufferPoolFactory;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.*;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Utility class to generate mock aggregation files
 * Created by gaurav.ashok on 27/03/17.
 */
public class MockAggregationWindow {

    public static AggregationWindowStorage buildMockAggregationWindowStorage(AsyncStorage asyncStorage) {
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(10);

        GenericObjectPool bufferPool = new GenericObjectPool<>(new ByteBufferPoolFactory(10_000_000, false), poolConfig);

        MetricRegistry mockMetricRegistry = mock(MetricRegistry.class);
        com.codahale.metrics.Timer mockTimer = mock(com.codahale.metrics.Timer.class);
        when(mockMetricRegistry.timer(any())).thenReturn(mockTimer);

        return new AggregationWindowStorage("profiles", asyncStorage, bufferPool, mockMetricRegistry);
    }

    public static FinalizedAggregationWindow buildAggregationWindow(String time, Supplier<String> stackTraces, int durationInSeconds) throws Exception {

        LocalDateTime lt = LocalDateTime.parse(time, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        MutableInt sampleCount = new MutableInt(0);
        FinalizedCpuSamplingAggregationBucket cpuSampleBucket = buildTree(stackTraces, sampleCount);

        int sampleCount1 = sampleCount.getValue() / 2;
        int sampleCount2 = sampleCount.getValue() - sampleCount1;

        FinalizedAggregationWindow window = new FinalizedAggregationWindow("app1", "cluster1", "proc1", lt, lt.plusMinutes(30), durationInSeconds, buildProfilesWorkInfo(lt ,sampleCount1, sampleCount2), cpuSampleBucket);

        return window;
    }

    private static FinalizedCpuSamplingAggregationBucket buildTree(Supplier<String> stacktraces, MutableInt sampleCount) throws IOException {
        CpuSamplingTraceDetail traceDetail = new CpuSamplingTraceDetail();

        MethodIdLookup lookup = new MethodIdLookup();
        CpuSamplingFrameNode global = traceDetail.getGlobalRoot();

        List<List<String>> stackTraces = new ObjectMapper().readValue(stacktraces.get(), List.class);

        int frameCounts = 0;
        int stackTracesCount = 0;
        for(List<String> st : stackTraces) {
            stackTracesCount++;
            global.incrementOnStackSamples();
            traceDetail.incrementSamples();

            CpuSamplingFrameNode node = global;
            int lineNo;
            int methodId;
            String methodName;
            for(String method : st) {
                if(method.split(":").length == 1){
                    lineNo = 0;                                             // some default line no.
                    methodName = method;
                }else{
                    lineNo = Integer.parseInt(method.split(":")[1]);
                    methodName = method.split(":")[0];
                }
                methodId = lookup.getOrAdd(methodName);
                node = node.getOrAddChild(methodId, lineNo);
                node.incrementOnStackSamples();
                frameCounts++;
            }
            node.incrementOnCpuSamples();
        }

        System.out.println("frame counts: " + frameCounts);
        System.out.println("stacktrace counts: " + stackTracesCount);

        sampleCount.setValue(stackTracesCount);

        return new FinalizedCpuSamplingAggregationBucket(lookup, buildMap("full-app-trace", traceDetail));
    }

    private static Map<Long, FinalizedProfileWorkInfo> buildProfilesWorkInfo(LocalDateTime aggregationStart, int count1, int count2) {

        AggregatedProfileModel.RecorderInfo r1 = AggregatedProfileModel.RecorderInfo.newBuilder()
                .setIp("192.168.1.1")
                .setHostname("some-box-1")
                .setAppId("app1")
                .setInstanceGroup("ig1")
                .setCluster("cluster1")
                .setInstanceId("instance1")
                .setProcessName("svc1")
                .setVmId("vm1")
                .setZone("chennai-1")
                .setInstanceType("c1.xlarge").build();

        AggregatedProfileModel.RecorderInfo r2 = AggregatedProfileModel.RecorderInfo.newBuilder()
                .setIp("192.168.1.2")
                .setHostname("some-box-2")
                .setAppId("app1")
                .setInstanceGroup("ig1")
                .setCluster("cluster1")
                .setInstanceId("instance2")
                .setProcessName("svc1")
                .setVmId("vm2")
                .setZone("chennai-1")
                .setInstanceType("c1.xlarge").build();

        FinalizedProfileWorkInfo wi = new FinalizedProfileWorkInfo(1, r1, AggregationState.COMPLETED, aggregationStart.plusSeconds(10), aggregationStart.plusSeconds(10 + 60), 60,
                buildMap("full-app-trace", 5),
                buildMap(AggregatedProfileModel.WorkType.cpu_sample_work, count1)
        );

        FinalizedProfileWorkInfo wi2 = new FinalizedProfileWorkInfo(1, r2, AggregationState.RETRIED, aggregationStart.plusSeconds(24), aggregationStart.plusSeconds(24 + 60), 60,
                buildMap("full-app-trace", 10),
                buildMap(AggregatedProfileModel.WorkType.cpu_sample_work, count2)
        );

        return buildMap(101l, wi, 102l, wi2);
    }

    private static <K,V> Map<K, V> buildMap(Object... objects) {
        assert objects.length % 2 == 0;

        Map<K, V> map = new HashMap<>();
        for(int i = 0;i < objects.length; i += 2) {
            map.put((K)objects[i], (V)objects[i+1]);
        }

        return map;
    }

    public static class FileLoader implements Supplier<String> {

        String data;

        public FileLoader(String path) throws IOException {
            this.data = new ObjectMapper().readValue(new FileInputStream(path), String.class);
        }

        @Override
        public String get() {
            return data;
        }
    }

    private AggregatedProfileModel.Header getHeader() {
        ZonedDateTime now = ZonedDateTime.parse("2017-02-07T07:30:10Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return AggregatedProfileModel.Header.newBuilder().setAppId("app1")
                .setClusterId("cluster1")
                .setProcId("proc1")
                .setAggregationStartTime(now.toString())
                .setAggregationEndTime(now.plusMinutes(30).toString())
                .setFormatVersion(1)
                .setWorkType(AggregatedProfileModel.WorkType.cpu_sample_work)
                .build();
    }

    public static class HashMapBasedStorage implements AsyncStorage {

        Map<String, byte[]> data = new HashMap<>();

        @Override
        public CompletableFuture<Void> storeAsync(String path, InputStream content, long length) {
            try {
                byte[] bytes = new byte[(int)length];
                int readBytes = content.read(bytes, 0, (int)length);
                assert readBytes == length : "couldn't read " + length + " bytes for storing";
                data.put(path, bytes);
                content.close();
            }
            catch(IOException e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<InputStream> fetchAsync(String path) {
            if(data.containsKey(path)) {
                return CompletableFuture.completedFuture(new ByteArrayInputStream(data.get(path)));
            }
            else {
                return CompletableFuture.supplyAsync(() -> {
                    throw new ObjectNotFoundException(path);
                });
            }
        }

        @Override
        public CompletableFuture<Set<String>> listAsync(String prefixPath, boolean recursive) {
            throw new UnsupportedOperationException("list operation not supported");
        }
    }

    // TODO: Remove after perf test. Below 2 methods show different ways to serialize proto objects.
    // A temp byteBuffer is reused everytime to write a abstractMessage. This avoids the creation of temp buffer everytime
    // you want to write the message but its content is copied to the outputStream.
    public void testCodedOutputStream_firstWriteToByteBufferThenCopyToOutputStream() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(1000);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(20_000_000);
        CodedOutputStream finalout = CodedOutputStream.newInstance(bos);

        long milli = System.currentTimeMillis();
        int count = 0;
        int limit = 1_000_000;

        for(count = 0; count < limit; count++) {
            AggregatedProfileModel.Header h = getHeader();

            CodedOutputStream cos = CodedOutputStream.newInstance(b);
            h.writeTo(cos);
            cos.flush();

            int size = b.position();
            b.flip();

            finalout.writeUInt32NoTag(size);
            finalout.write(b);
            finalout.flush(); //flush to array outstream

            b.clear();

            if(bos.size() > 10_000_000) {
                bos.reset();
            }
        }

        long milli2 = System.currentTimeMillis();

        System.out.println("Time took: " + (milli2 - milli));
    }

    // here message is directly written to outputStream but a temp buffer is always created in the process.
    public void testCodedOutputStream_directlyWriteToOutputStreamWUsingWriteDelimited() throws Exception {

        ByteArrayOutputStream bos = new ByteArrayOutputStream(20_000_000);

        long milli = System.currentTimeMillis();
        int count = 0;
        int limit = 1_000_000;

        for(count = 0; count < limit; count++) {
            AggregatedProfileModel.Header h = getHeader();

            h.writeDelimitedTo(bos);

            if(bos.size() > 10_000_000) {
                bos.reset();
            }
        }

        long milli2 = System.currentTimeMillis();

        System.out.println("Time took: " + (milli2 - milli));
    }
}
