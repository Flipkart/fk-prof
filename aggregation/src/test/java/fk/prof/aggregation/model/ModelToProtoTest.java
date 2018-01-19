package fk.prof.aggregation.model;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.idl.Profile;
import fk.prof.idl.Profile.*;
import fk.prof.idl.WorkEntities;
import fk.prof.idl.WorkEntities.WorkType;
import fk.prof.aggregation.state.AggregationState;
import org.hamcrest.core.IsCollectionContaining;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;

import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

/**
 * @author gaurav.ashok
 */
public class ModelToProtoTest {

    @Test
    public void testAggregationFileName() {
        String base = "profiles";
        String appid = "app1";
        String clusterid = "cluster1";
        String procid = "proc1";
        ZonedDateTime dateTime = ZonedDateTime.now(Clock.systemUTC());
        int duration = 60;

        for(WorkType workType: WorkType.values()) {
            AggregatedProfileNamingStrategy filename = new AggregatedProfileNamingStrategy(base, 1, appid, clusterid, procid, dateTime, duration, workType);
            AggregatedProfileNamingStrategy parsedFilename = AggregatedProfileNamingStrategy.fromFileName(filename.getFileName(0));
            assertThat(parsedFilename, is(filename));
        }

        // summary file
        AggregatedProfileNamingStrategy summaryFilename = new AggregatedProfileNamingStrategy(base, 1, appid, clusterid, procid, dateTime, duration);
        AggregatedProfileNamingStrategy parsed = AggregatedProfileNamingStrategy.fromFileName(summaryFilename.getFileName(0));
        assertThat(parsed, is(summaryFilename));

        assertThat(parsed.isSummaryFile, is(true));
    }

    @Test
    public void testProfileSummary_aggregationWindowsShouldProperlyBuildSummaryProto() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        FinalizedAggregationWindow window = new FinalizedAggregationWindow("app1", "cluster1", "proc1", now, now.plusSeconds(1200), 1200,
                buildMap(
                        101l, new FinalizedProfileWorkInfo(1, null, AggregationState.COMPLETED, now.plusSeconds(10), now.plusSeconds(90), 80, buildMap("trace1", 5, "trace2", 10), buildMap(WorkType.cpu_sample_work, 100)),
                        102l, new FinalizedProfileWorkInfo(1, null, AggregationState.ABORTED, now.plusSeconds(100), now.plusSeconds(200), 100, buildMap("trace1", 10, "trace2", 10), buildMap(WorkType.cpu_sample_work, 1000))
                        ),
                buildRecordingPolicy(new HashSet<>(Arrays.asList(WorkType.cpu_sample_work))),
                null
                );

        Iterable<ProfileWorkInfo> infos = window.buildProfileWorkInfoProto(WorkType.cpu_sample_work, buildTraceList("trace1", "trace2"));

        assertNotNull(infos);

        List<ProfileWorkInfo> infosAsList = new ArrayList();
        infos.forEach(infosAsList::add);

        // all profiles are group under 1 source, because source info is not being populated yet.
        assertEquals(infosAsList.size(), 2);
        assertThat(infosAsList.get(0).getStatus(), anyOf(is(AggregationStatus.Completed), is(AggregationStatus.Aborted)));

        ProfileWorkInfo info1, info2;

        if(infosAsList.get(0).getStatus().equals(AggregationStatus.Completed)) {
            info1 = infosAsList.get(0);
            info2 = infosAsList.get(1);
        }
        else {
            info1 = infosAsList.get(1);
            info2 = infosAsList.get(0);
        }

        assertThat(info1.getDuration(), is(80));
        assertThat(info2.getDuration(), is(100));

        assertThat(info1.getStartOffset(), is(10));
        assertThat(info2.getStartOffset(), is(100));

        assertThat(info1.getSampleCountCount(), is(1));
        assertThat(info1.getSampleCountList(), IsCollectionContaining.hasItems(ProfileWorkInfo.SampleCount.newBuilder().setWorkType(WorkType.cpu_sample_work).setSampleCount(100).build()));

        assertThat(info2.getSampleCountCount(), is(1));
        assertThat(info2.getSampleCountList(), IsCollectionContaining.hasItems(ProfileWorkInfo.SampleCount.newBuilder().setWorkType(WorkType.cpu_sample_work).setSampleCount(1000).build()));

        assertThat(info1.getTraceCoverageMapCount(), is(2));
    }

    @Test
    public void testBuildWorkInfo_profileWorkInfoShouldProperlyBuildWorkInfoProto() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());

        FinalizedProfileWorkInfo wi1 = new FinalizedProfileWorkInfo(1, null, AggregationState.COMPLETED, now, now.plusMinutes(1), 60,
                buildMap("trace1", 5, "trace2", 10, "trace3", 15),
                buildMap(WorkType.cpu_sample_work, 100));

        ProfileWorkInfo workInfo = wi1.buildProfileWorkInfoProto(WorkType.cpu_sample_work, now, buildTraceList("trace1", "trace2"));

        assertEquals(workInfo.getDuration(), 60);
        assertEquals(workInfo.getRecorderVersion(), 1);
        assertThat(workInfo.getSampleCountCount(), is(1));
        assertThat(workInfo.getSampleCountList(), IsCollectionContaining.hasItems(
                ProfileWorkInfo.SampleCount.newBuilder().setWorkType(WorkType.cpu_sample_work).setSampleCount(100).build()
        ));
        assertEquals(workInfo.getStartOffset(), 0);
        assertEquals(workInfo.getStatus(), AggregationStatus.Completed);
        assertEquals(workInfo.getTraceCoverageMapCount(), 2);

        assertThat(workInfo.getTraceCoverageMap(0).getTraceCtxIdx(), is(0));
        assertThat(workInfo.getTraceCoverageMap(0).getCoveragePct(), is(5.0f));

        assertThat(workInfo.getTraceCoverageMap(1).getTraceCtxIdx(), is(1));
        assertThat(workInfo.getTraceCoverageMap(1).getCoveragePct(), is(10.0f));
    }

    @Test
    public void testStackTraceTreeToProto_cpuSamplingTraceDetailsShouldSerializeStacktraceTreeInBatches() throws Exception {
        CpuSamplingTraceDetail traceDetail = new CpuSamplingTraceDetail();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        /** add nodes in this order:
         *  A
         *  |_ B
         *  |  |_ C
         *  |     |_ D
         *  |_ E
         */

        traceDetail
                // A
                .getGlobalRoot()
                // B
                .getOrAddChild(1, 0)
                // C
                .getOrAddChild(2, 0)
                // D
                .getOrAddChild(3, 0);

        traceDetail.getGlobalRoot()
                // E
                .getOrAddChild(4, 0);

        FinalizedCpuSamplingAggregationBucket.NodeVisitor visitor = new FinalizedCpuSamplingAggregationBucket.NodeVisitor(out, 3, 0);
        traceDetail.getGlobalRoot().traverse(visitor);
        visitor.end();

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        FrameNodeList nodes = FrameNodeList.parseDelimitedFrom(in);

        assertNotNull(nodes);
        assertThat(nodes.getFrameNodesCount(), is(3));
        assertThat(nodes.getFrameNodes(0).getMethodId(), is(0));
        assertThat(nodes.getFrameNodes(1).getMethodId(), is(1));
        assertThat(nodes.getFrameNodes(2).getMethodId(), is(2));

        FrameNodeList nextNodes = FrameNodeList.parseDelimitedFrom(in);
        assertNotNull(nextNodes);
        assertThat(nextNodes.getFrameNodesCount(), is(2));
        assertThat(nextNodes.getFrameNodes(0).getMethodId(), is(3));
        assertThat(nextNodes.getFrameNodes(1).getMethodId(), is(4));

        assertThat(in.available(), is(0));
    }

    private Set<RecorderDetails> recorders() {
        Set<RecorderDetails> recorders = new HashSet<>();
        recorders.addAll(
                Arrays.asList(
                    Profile.RecorderDetails.newBuilder()
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
                    Profile.RecorderDetails.newBuilder()
                            .setIp("192.168.1.2")
                            .setHostname("some-box-2")
                            .setAppId("app1")
                            .setInstanceGroup("ig1")
                            .setCluster("cluster1")
                            .setInstanceId("instance2")
                            .setProcessName("svc1")
                            .setVmId("vm2")
                            .setZone("chennai-1")
                            .setInstanceType("c1.xlarge").build()));
        return recorders;
    }

    private RecordingPolicy buildRecordingPolicy(Set<WorkType> workTypes) {
        List<WorkEntities.Work> workList = new ArrayList<>();
        for(WorkType workType: workTypes) {
            workList.add(WorkEntities.Work.newBuilder().setWType(workType).build());
        }
        return RecordingPolicy.newBuilder().setDuration(60).setMinHealthy(10)
            .setDescription("Test policy").setCoveragePct(100).addAllWork(workList).build();
    }

    private TraceCtxDetail buildTraceCtxDetails(int idx, int count) {
        return TraceCtxDetail.newBuilder().setTraceIdx(idx).setSampleCount(count).build();
    }

    private TraceCtxNames buildTraceList(String... traces) {
        return TraceCtxNames.newBuilder().addAllName(Arrays.asList(traces)).build();
    }

    private TraceCtxDetailList buildTraceList(Object... objects) {
        assert objects.length % 2 == 0;

        TraceCtxDetailList.Builder builder = TraceCtxDetailList.newBuilder();

        for(int i = 0 ; i < objects.length; i += 2) {
            builder.addTraceCtx(buildTraceCtxDetails((Integer)objects[i], (Integer)objects[i+1]));
        }

        return builder.build();
    }

    private <K,V> Map<K, V> buildMap(Object... objects) {
        assert objects.length % 2 == 0;

        Map<K, V> map = new HashMap<>();
        for(int i = 0;i < objects.length; i += 2) {
            map.put((K)objects[i], (V)objects[i+1]);
        }

        return map;
    }
}
