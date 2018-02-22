package fk.prof.userapi.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedOutputStream;
import fk.prof.aggregation.model.*;
import fk.prof.aggregation.state.AggregationState;
import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;
import org.apache.commons.lang3.mutable.MutableInt;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Utility class to generate mock aggregation files
 * Created by gaurav.ashok on 27/03/17.
 */
public class MockAggregationWindow {

    public static FinalizedAggregationWindow buildAggregationWindow(String time, Supplier<String> stackTraces, int durationInSeconds) throws Exception {

        LocalDateTime lt = LocalDateTime.parse(time, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        MutableInt sampleCount = new MutableInt(0);
        FinalizedCpuSamplingAggregationBucket cpuSampleBucket = buildTree(stackTraces, sampleCount);

        int sampleCount1 = sampleCount.getValue() / 2;
        int sampleCount2 = sampleCount.getValue() - sampleCount1;

        Map<WorkEntities.WorkType, FinalizedWorkSpecificAggregationBucket> workSpecificBuckets = new HashMap<>();
        workSpecificBuckets.put(WorkEntities.WorkType.cpu_sample_work, cpuSampleBucket);
        FinalizedAggregationWindow window = new FinalizedAggregationWindow("app1", "cluster1", "proc1", lt, lt.plusMinutes(30), durationInSeconds,
            buildProfilesWorkInfo(lt ,sampleCount1, sampleCount2),
            buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkEntities.WorkType.cpu_sample_work))),
            workSpecificBuckets);

        return window;
    }

    private static FinalizedCpuSamplingAggregationBucket buildTree(Supplier<String> stacktraces, MutableInt sampleCount) throws IOException {
        CpuSamplingTraceDetail traceDetail = new CpuSamplingTraceDetail();

        MethodIdLookup lookup = new MethodIdLookup();
        CpuSamplingFrameNode global = traceDetail.getGlobalRoot();

        List<List<String>> stackTraces = new ObjectMapper().readValue(stacktraces.get(), List.class);

        Random random = new Random();

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
                    lineNo = random.nextInt(5);
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

        Profile.RecorderDetails r1 = Profile.RecorderDetails.newBuilder()
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

        Profile.RecorderDetails r2 = Profile.RecorderDetails.newBuilder()
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
                buildMap(WorkEntities.WorkType.cpu_sample_work, count1)
        );

        FinalizedProfileWorkInfo wi2 = new FinalizedProfileWorkInfo(1, r2, AggregationState.RETRIED, aggregationStart.plusSeconds(24), aggregationStart.plusSeconds(24 + 60), 60,
                buildMap("full-app-trace", 10),
                buildMap(WorkEntities.WorkType.cpu_sample_work, count2)
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

    private Profile.Header getHeader() {
        ZonedDateTime now = ZonedDateTime.parse("2017-02-07T07:30:10Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return Profile.Header.newBuilder().setAppId("app1")
                .setClusterId("cluster1")
                .setProcId("proc1")
                .setAggregationStartTime(now.toString())
                .setAggregationEndTime(now.plusMinutes(30).toString())
                .setFormatVersion(1)
                .setWorkType(WorkEntities.WorkType.cpu_sample_work)
                .build();
    }

    private static Profile.RecordingPolicy buildRecordingPolicy(Set<WorkEntities.WorkType> workTypes) {
        List<WorkEntities.Work> workList = new ArrayList<>();
        for(WorkEntities.WorkType workType: workTypes) {
            workList.add(WorkEntities.Work.newBuilder().setWType(workType).build());
        }
        return Profile.RecordingPolicy.newBuilder().setDuration(60).setMinHealthy(10)
            .setDescription("Test policy").setCoveragePct(100).addAllWork(workList).build();
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
            Profile.Header h = getHeader();

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
            Profile.Header h = getHeader();

            h.writeDelimitedTo(bos);

            if(bos.size() > 10_000_000) {
                bos.reset();
            }
        }

        long milli2 = System.currentTimeMillis();

        System.out.println("Time took: " + (milli2 - milli));
    }
}
