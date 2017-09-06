package fk.prof.recorder;

import fk.prof.MergeSemantics;
import fk.prof.recorder.cpuburn.Burn20Of100;
import fk.prof.recorder.cpuburn.Burn80Of100;
import fk.prof.recorder.cpuburn.WrapperA;
import fk.prof.recorder.main.*;
import fk.prof.recorder.utils.AgentRunner;
import fk.prof.recorder.utils.TestBackendServer;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.hamcrest.Matcher;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openjdk.jmh.infra.Blackhole;
import recording.Recorder;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

import static fk.prof.recorder.AssociationTest.assertRecorderInfoAllGood_AndGetTick;
import static fk.prof.recorder.WorkHandlingTest.*;
import static fk.prof.recorder.utils.Matchers.approximately;
import static fk.prof.recorder.utils.Matchers.approximatelyBetween;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class CpuSamplingTest {
    public static final int CPU_SAMPLING_FREQ = 100;
    public static final int CPU_SAMPLING_MAX_FRAMES = 50;
    public static final int CONTROLLER_ID = 2;
    public static final int CPU_SAMPLING_WORK_ID = 42;
    public static final String DUMMY_ROOT_NODE_CLASS_NAME = "ROOT";
    public static final String DUMMY_ROOT_NODE_FN_NAME = "~ root ~";
    public static final String DUMMY_ROOT_NOTE_FN_SIGNATURE = "()V";
    private static final String USUAL_RECORDER_ARGS = "service_endpoint=http://127.0.0.1:8080," +
            "ip=10.20.30.40," +
            "host=foo-host," +
            "app_id=bar-app," +
            "inst_grp=baz-grp," +
            "cluster=quux-cluster," +
            "inst_id=corge-iid," +
            "proc=grault-proc," +
            "vm_id=garply-vmid," +
            "zone=waldo-zone," +
            "inst_typ=c0.small," +
            "backoff_start=2," +
            "backoff_max=5," +
            "poll_itvl=1," +
            "log_lvl=trace," +
            "stats_syslog_tag=foobar";
    private static final String DEFAULT_CTX_NAME = "~ OTHERS ~";
    private static final String UNKNOWN_CTX_NAME = "~ UNKNOWN ~";
    private TestBackendServer server;
    private Function<byte[], byte[]>[] association = new Function[2];
    private Function<byte[], byte[]>[] poll = new Function[18];
    private Function<byte[], byte[]>[] profile = new Function[2];
    private Future[] assocAction;
    private Future[] pollAction;
    private Future[] profileAction;
    private AgentRunner runner;
    private TestBackendServer associateServer;

    @Before
    public void setUp() {
        server = new TestBackendServer(8080);
        associateServer = new TestBackendServer(8090);
        assocAction = server.register("/association", association);
        pollAction = associateServer.register("/poll", poll);
        profileAction = associateServer.register("/profile", profile);
    }

    @After
    public void tearDown() {
        assertThat(runner.stop(), is(true));
        server.stop();
        associateServer.stop();
    }

    @Test
    public void should_TrackAssignedWork() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        association[0] = pointToAssociate(recInfo, 8090);
        WorkHandlingTest.PollReqWithTime pollReqs[] = new WorkHandlingTest.PollReqWithTime[poll.length];
        for (int i = 0; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }

        runner = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        long idx = 0;
        Matcher<Long> recorderTickMatcher = is(0l);
        long previousTick;
        for (WorkHandlingTest.PollReqWithTime prwt : pollReqs) {
            previousTick = assertRecorderInfoAllGood_AndGetTick(prwt.req.getRecorderInfo(), recorderTickMatcher, AssociationTest.rc(true));
            recorderTickMatcher = greaterThan(previousTick);
            assertItHadNoWork(prwt.req.getWorkLastIssued(), idx == 0 ? idx : idx + 99);
            if (idx > 0) {
                assertThat("idx = " + idx, prwt.time - prevTime, approximatelyBetween(1000l, 2000l)); //~1 sec tolerance
            }
            prevTime = prwt.time;
            idx++;
        }
    }

    @Test
    public void should_Track_And_Retire_CpuProfileWork() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            put(wrap(DEFAULT_CTX_NAME), zeroSampleRoot());
            put(wrap(UNKNOWN_CTX_NAME), zeroSampleRoot());
            put(wrap("inferno"), rootMatcher(childrenMatcher(
                    nodeMatcher(Burn20And80PctCpu.class, "main", "([Ljava/lang/String;)V", 0, 1, childrenMatcher(
                            nodeMatcher(Burn20And80PctCpu.class, "burnCpu", "()V", 0, 1, childrenMatcher(
                                    nodeMatcher(Burn20Of100.class, "burn", "()V", 0, 1, childrenMatcher(
                                            nodeMatcher(Blackhole.class, "consumeCPU", "(J)V", 20, 10, Collections.emptySet()))),
                                    nodeMatcher(WrapperA.class, "burnSome", "(S)V", 0, 1, childrenMatcher(
                                            nodeMatcher(Burn80Of100.class, "burn", "()V", 0, 1, childrenMatcher(
                                                    nodeMatcher(Blackhole.class, "consumeCPU", "(J)V", 80, 10, Collections.emptySet()))))))))))));
        }}, new TraceIdPivotResolver(), new HashMap<Integer, TraceInfo>(), new HashMap<Integer, ThreadInfo>(), new HashMap<Long, MthdInfo>(), new HashMap<String, SampledStackNode>());
    }

    @Test
    public void should_Report_Alloc_Burn() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(AllocBurner.class.getCanonicalName(), USUAL_RECORDER_ARGS + ",noctx_cov_pct=50");
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        //debug aid
        //Map<Integer, TraceInfo> traceInfoMap = new HashMap<>();
        //Map<Integer, ThreadInfo> thdInfoMap = new HashMap<>();
        //Map<Long, MthdInfo> mthdInfoMap = new HashMap<>();
        //Map<String, SampledStackNode> aggregations = new HashMap<>();
        //makeTree(profileEntries, false, new TraceIdPivotResolver(), traceInfoMap, thdInfoMap, mthdInfoMap, aggregations);

        assertOnStackPctIsAbove(Pattern.compile(".*ParallelScavengeHeap::mem_allocate.*"), 90.0, profileEntries);
    }

    @Test
    public void should_Report_Intrinsic_Burn() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(IntrinsicBurner.class.getCanonicalName(), USUAL_RECORDER_ARGS + ",noctx_cov_pct=50");
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        //debug aid
        //Map<Integer, TraceInfo> traceInfoMap = new HashMap<>();
        //Map<Integer, ThreadInfo> thdInfoMap = new HashMap<>();
        //Map<Long, MthdInfo> mthdInfoMap = new HashMap<>();
        //Map<String, SampledStackNode> aggregations = new HashMap<>();
        //makeTree(profileEntries, false, new TraceIdPivotResolver(), traceInfoMap, thdInfoMap, mthdInfoMap, aggregations);

        assertOnStackPctIsAbove(Pattern.compile(".*(sin|cos).*"), 2.0, profileEntries);
    }

    private void assertOnStackPctIsAbove(Pattern pattern, double minPct, List<Recorder.Wse> entries) {
        Set<Long> methodIds = new TreeSet<>();
        long matchCount = 0;
        long totalCount = 0;
        for (Recorder.Wse entry : entries) {
            assertThat(entry.hasCpuSampleEntry(), is(true));
            for (Recorder.MethodInfo methodInfo : entry.getIndexedData().getMethodInfoList()) {
                String methodName = methodInfo.getMethodName();
                if (pattern.matcher(methodName).matches()) {
                    methodIds.add(methodInfo.getMethodId());
                }
            }
            assertThat(methodIds.size(), greaterThan(0));
            Recorder.StackSampleWse cpuSampleEntry = entry.getCpuSampleEntry();
            for (Recorder.StackSample sample : cpuSampleEntry.getStackSampleList()) {
                totalCount++;
                for (Recorder.Frame frame : sample.getFrameList()) {
                    if (methodIds.contains(frame.getMethodId())) {
                        matchCount++;
                        break;
                    }
                }
            }
        }

        double pct = ((double) matchCount * 100) / totalCount;
        //System.out.println("pct = " + pct);
        assertThat(pct, greaterThan(minPct));
    }

    @Test
    public void should_ShutdownCleanly_On_SIGTERM_WhileProfiling() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        int delay = 1;
        int workAllocatingPoll = 1;
        int gracePeriod = 1;
        int duration = 10;
        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES, duration, delay, workAllocatingPoll);

        runner = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        int runningWorkPoll = workAllocatingPoll + delay + gracePeriod;
        pollAction[runningWorkPoll].get(runningWorkPoll + 4, TimeUnit.SECONDS); //some grace time
        
        assertThat(runner.stop(), is(true));//this actually waits for the process to be reaped
        assertThat(runner.exitCode(), is(128 + 15)); //15 == SIGTERM

        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[runningWorkPoll].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, gracePeriod);
        
        //This assertion requires that runner.stop or something else above this assertion in this test waits for target process to return
        //   else this assertion would be meaningless (because target-process is live implies more polls can happen).
        int firstMissedPollIdx = runningWorkPoll;
        while ((firstMissedPollIdx < pollReqs.length) && 
                (pollAction[firstMissedPollIdx].isDone())) {
            firstMissedPollIdx++;
        }
        assertThat(firstMissedPollIdx, lessThan(duration + delay + workAllocatingPoll + gracePeriod));//make sure that the target-process died while profiling (so test indeed produced the intended condition)
        assertThat(firstMissedPollIdx, lessThan(poll.length));
    }

    @Test
    public void should_respect_Coverage_and_MergeSemantic() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(Burn50And50PctCpu.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        HashMap<Integer, TraceInfo> traceInfoMap = new HashMap<>();
        HashMap<String, SampledStackNode> aggregations = new HashMap<>();
        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            Class klass = Burn50And50PctCpu.class;
            put(wrap(DEFAULT_CTX_NAME), zeroSampleRoot());
            put(wrap(UNKNOWN_CTX_NAME), zeroSampleRoot());
            put(wrap("100_pct_single_inferno"), generate_Main_Burn_Immolate_BacktraceMatcher(66, klass, 25));
            put(wrap("50_pct_duplicate_inferno"), generate_Main_Burn_Immolate_BacktraceMatcher(33, klass, 25));
            put(wrap("50_pct_duplicate_inferno_child"), generate_Main_Burn_Immolate_BacktraceMatcher(33, klass, 25));
        }}, new TraceIdPivotResolver(), traceInfoMap, new HashMap<Integer, ThreadInfo>(), new HashMap<Long, MthdInfo>(), aggregations);

        assertThat(traceInfoMap.values(), hasItems(new TraceInfo("100_pct_single_inferno", 100, MergeSemantics.MERGE_TO_PARENT), new TraceInfo("50_pct_duplicate_inferno", 50, MergeSemantics.MERGE_TO_PARENT), new TraceInfo("50_pct_duplicate_inferno_child", 50, MergeSemantics.DUPLICATE)));

        //check BCI and Line-no was posted correctly, blackhole consumeCPU call should almost always be on stack 

        Map<ImmutablePair<Integer, Integer>, MutableInt> bci_lineNo_Histo = new HashMap<>();
        for (SampledStackNode node : aggregations.values()) {
            buildBciLineNoHistogram(node, bci_lineNo_Histo, Burn50And50PctCpu.class, "immolate", "(I)V");
        }
        Set<ImmutablePair<Integer, Integer>> blackholeConsumeCpuCallSites = new HashSet<ImmutablePair<Integer, Integer>>() {{
            add(new ImmutablePair<Integer, Integer>(24, 45));
            add(new ImmutablePair<Integer, Integer>(147, 52));//later we should have a javap call here and should decide bci from java-disassembly, because different compiler versions may generate slightly different number of instructions or order or instructions, which will mess up this hard-coded value
        }};
        int blackholeCallSites = 0, allCallSites = 0;
        for (Map.Entry<ImmutablePair<Integer, Integer>, MutableInt> entry : bci_lineNo_Histo.entrySet()) {
            Integer ctr = entry.getValue().getValue();
            if (blackholeConsumeCpuCallSites.contains(entry.getKey())) {
                blackholeCallSites += ctr;
            }
            allCallSites += ctr;
        }

        double ratio = (double) blackholeCallSites / allCallSites;
        double expectedMin = 0.9;
        assertThat("The line-no/bci distribution was somehow not right (expected " + expectedMin + "x calls to be on hot fn call-site, but it was only " + ratio + "x (details: " + bci_lineNo_Histo + ")", ratio, greaterThan(expectedMin));
    }

    private void buildBciLineNoHistogram(SampledStackNode node, Map<ImmutablePair<Integer, Integer>, MutableInt> bci_lineNo_histo, Class klass, String fnName, String sig) {
        buildBciLineNoHistogram(node, bci_lineNo_histo, fnName, sig, makeClassName(klass.getCanonicalName()), makeKlassFileName(klass.getSimpleName()));
    }

    private String makeKlassFileName(String klassSimpleName) {
        return klassSimpleName + ".java";
    }

    private void buildBciLineNoHistogram(SampledStackNode node, Map<ImmutablePair<Integer, Integer>, MutableInt> bci_lineNo_histo, String fnName, String sig, String klassName, final String klassFileName) {
        if (fnName.equals(node.fnName) && sig.equals(node.fnSig) && klassName.equals(node.klass) && klassFileName.equals(node.file)) {
            ImmutablePair<Integer, Integer> key = new ImmutablePair<>(node.bci, node.lineNo);
            MutableInt sampleCount = bci_lineNo_histo.get(key);
            if (sampleCount == null) {
                bci_lineNo_histo.put(key, new MutableInt(node.onStackSampleCount));
            } else {
                sampleCount.add(node.onStackSampleCount);
            }
        }
        for (SampledStackNode child : node.children.values()) {
            buildBciLineNoHistogram(child, bci_lineNo_histo, fnName, sig, klassName, klassFileName);
        }
    }

    private StackNodeMatcher generate_Main_Burn_Immolate_BacktraceMatcher(final int expectedOncpuPct, final Class klass, final int pctMatchTolerance) {
        return rootMatcher(childrenMatcher(
                nodeMatcher(klass, "main", "([Ljava/lang/String;)V", 0, 1, childrenMatcher(
                        nodeMatcher(klass, "immolate", "(I)V", 0, 1, childrenMatcher(
                                nodeMatcher(Blackhole.class, "consumeCPU", "(J)V", expectedOncpuPct, pctMatchTolerance, Collections.emptySet())))))));
    }

    @Test
    public void shouldReport_Threads() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(MultiThreadedCpuBurner.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        HashMap<Integer, ThreadInfo> thdInfoMap = new HashMap<>();
        HashMap<Long, MthdInfo> mthdInfoMap = new HashMap<>();
        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            put(wrap("foo-the-thd"), generate_Thread_Runnable_Immolate_BacktraceMatcher(50));
            put(wrap("bar-the-thd"), generate_Thread_Runnable_Immolate_BacktraceMatcher(50));
        }}, new ThreadIdPivotResolver(), new HashMap<Integer, TraceInfo>(), thdInfoMap, mthdInfoMap, new HashMap<String, SampledStackNode>());

        assertThat(thdInfoMap.values(), hasItems(new ThreadInfo("foo-the-thd", 6, false), new ThreadInfo("bar-the-thd", 5, true)));
        assertThat(mthdInfoMap.values(), hasItems(
                new MthdInfo("Lfk/prof/recorder/main/CpuBurningRunnable;", "run", "()V", "CpuBurningRunnable.java"),
                new MthdInfo("Lorg/openjdk/jmh/infra/Blackhole;", "consumeCPU", "(J)V", "Blackhole.java")));
    }

    @Test
    public void should_HandleCtxScoping() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(BurnCpuScoped.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        HashMap<Integer, TraceInfo> traceInfoMap = new HashMap<>();
        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            Class klass = BurnCpuScoped.class;
            put(wrap(DEFAULT_CTX_NAME), zeroSampleRoot());
            put(wrap(UNKNOWN_CTX_NAME), zeroSampleRoot());
            put(wrap("p100"), generate_Main_Burn_Immolate_BacktraceMatcher(33, klass, 25));
            put(wrap("p100 > c1"), generate_Main_Burn_Immolate_BacktraceMatcher(33, klass, 25));
            put(wrap("p100 > c1 > c2"), generate_Main_Burn_Immolate_BacktraceMatcher(33, klass, 25));
            put(wrap("c1"), rootMatcher(Collections.emptySet()));
            put(wrap("c2"), rootMatcher(Collections.emptySet()));
        }}, new TraceIdPivotResolver(), traceInfoMap, new HashMap<Integer, ThreadInfo>(), new HashMap<Long, MthdInfo>(), new HashMap<String, SampledStackNode>());
        assertThat(traceInfoMap.values(), hasItems(
                new TraceInfo("p100", 100, MergeSemantics.STACK_UP),
                TraceInfo.mergedTraceInfo("p100 > c1"),
                TraceInfo.mergedTraceInfo("p100 > c1 > c2"),
                new TraceInfo("c1", 0, MergeSemantics.PARENT_SCOPED),
                new TraceInfo("c2", 0, MergeSemantics.PARENT_SCOPED)));
    }

    @Test
    public void should_HandleCtxStacking() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(BurnCpuStacked.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        HashMap<Integer, TraceInfo> traceInfoMap = new HashMap<>();
        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            Class klass = BurnCpuStacked.class;
            put(wrap(DEFAULT_CTX_NAME), zeroSampleRoot());
            put(wrap(UNKNOWN_CTX_NAME), zeroSampleRoot());
            put(wrap("p50"), generate_Main_Burn_Immolate_BacktraceMatcher(25, klass, 25));
            put(wrap("c100"), generate_Main_Burn_Immolate_BacktraceMatcher(50, klass, 25));
        }}, new TraceIdPivotResolver(), traceInfoMap, new HashMap<Integer, ThreadInfo>(), new HashMap<Long, MthdInfo>(), new HashMap<String, SampledStackNode>());
        assertThat(traceInfoMap.values(), hasItems(
                new TraceInfo("p50", 50, MergeSemantics.PARENT_SCOPED),
                new TraceInfo("c100", 100, MergeSemantics.STACK_UP)));
    }

    private StackNodeMatcher zeroSampleRoot() {
        return rootMatcher(Collections.emptySet());
    }

    private StackNodeMatcher rootMatcher(Set<StackNodeMatcher> children) {
        return rootMatcher(DUMMY_ROOT_NODE_FN_NAME, DUMMY_ROOT_NOTE_FN_SIGNATURE, 0, 1, children);
    }

    @Test
    public void should_Report_NoCtxData() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, WorkHandlingTest.CPU_SAMPLING_MAX_FRAMES);

        runner = new AgentRunner(BurnHalfInHalfOut.class.getCanonicalName(), USUAL_RECORDER_ARGS + ",noctx_cov_pct=50");
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, CPU_SAMPLING_MAX_FRAMES);

        HashMap<Integer, TraceInfo> traceInfoMap = new HashMap<>();
        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            Class klass = BurnHalfInHalfOut.class;
            put(wrap(UNKNOWN_CTX_NAME), zeroSampleRoot());
            put(wrap(DEFAULT_CTX_NAME), generate_Main_Burn_Immolate_BacktraceMatcher(25, klass, 20));
            put(wrap("c100"), generate_Main_Burn_Immolate_BacktraceMatcher(50, klass, 20));
        }}, new TraceIdPivotResolver(), traceInfoMap, new HashMap<Integer, ThreadInfo>(), new HashMap<Long, MthdInfo>(), new HashMap<String, SampledStackNode>());
        assertThat(traceInfoMap.values(), hasItems(
                new TraceInfo(DEFAULT_CTX_NAME, 50, MergeSemantics.MERGE_TO_PARENT),
                new TraceInfo("c100", 100, MergeSemantics.MERGE_TO_PARENT)));
    }

    @Test
    public void should_SnipBacktrace_ToChosenLength() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<Recorder.Wse> profileEntries = new ArrayList<>();
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        int maxFrames = 2;
        PollReqWithTime[] pollReqs = stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, maxFrames);

        runner = new AgentRunner(BurnCpuUsingRunnable.class.getCanonicalName(), USUAL_RECORDER_ARGS);
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollIntervalIsGood(pollReqs, prevTime);

        assertPolledInStatusIsGood(pollReqs);

        assertRecordingHeaderIsGood(cpuSamplingWorkIssueTime, hdr, maxFrames);

        HashMap<String, SampledStackNode> aggregation = new HashMap<>();
        assertProfileCallAndContent(profileCalledSecondTime, profileEntries, new HashMap<String, StackNodeMatcher>() {{
            put(wrap(UNKNOWN_CTX_NAME), zeroSampleRoot());
            put(wrap(DEFAULT_CTX_NAME), zeroSampleRoot());
            put(wrap("inferno"), rootMatcher(childrenMatcher(
                    nodeMatcher(CpuBurningRunnable.class, "run", "()V", 0, 1, childrenMatcher(
                            nodeMatcher(Blackhole.class, "consumeCPU", "(J)V", 100, 10, Collections.emptySet()))))));
        }}, new TraceIdPivotResolver(), new HashMap<>(), new HashMap<Integer, ThreadInfo>(), new HashMap<Long, MthdInfo>(), aggregation);

        int snipped_sample_count = 0;
        int sample_count = 0;
        for (SampledStackNode node : aggregation.values()) {
            snipped_sample_count += getOnCpuSnippedCount(node);
            sample_count += node.onStackSampleCount;
        }
        double ratio = (double) snipped_sample_count / sample_count;
        assertThat("Snipped to total ratio was expected to be higher than 0.9, but was " + ratio, ratio, greaterThan(0.9));
    }

    private int getOnCpuSnippedCount(SampledStackNode node) {
        int sum = node.onCpuSnippedCount;
        for (SampledStackNode child : node.children.values()) {
            sum += getOnCpuSnippedCount(child);
        }
        return sum;
    }

    private StackNodeMatcher generate_Thread_Runnable_Immolate_BacktraceMatcher(final int expectedOncpuPct) {
        return rootMatcher(childrenMatcher(
                nodeMatcher(Thread.class, "run", "()V", 0, 1, childrenMatcher(
                        nodeMatcher(CpuBurningRunnable.class, "run", "()V", 0, 1, childrenMatcher(
                                nodeMatcher(Blackhole.class, "consumeCPU", "(J)V", expectedOncpuPct, 20, Collections.emptySet())))))));
    }

    private PollReqWithTime[] stubRecorderInteraction(List<Recorder.Wse> profileEntries, MutableObject<Recorder.RecordingHeader> hdr, MutableBoolean profileCalledSecondTime, String cpuSamplingWorkIssueTime, final int maxFrames) {
        return stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, cpuSamplingWorkIssueTime, maxFrames, 10, 2, 1);
    }

    private PollReqWithTime[] stubRecorderInteraction(List<Recorder.Wse> profileEntries, MutableObject<Recorder.RecordingHeader> hdr, MutableBoolean profileCalledSecondTime, String cpuSamplingWorkIssueTime, int maxFrames, int duration, int delay, final int workAllocatingPoll) {
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];

        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();

        association[0] = pointToAssociate(recInfo, 8090);

        for (int i = 0; i < poll.length; i++) {
            if (i == workAllocatingPoll) continue;
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }
        poll[workAllocatingPoll] = issueCpuProfilingWork(pollReqs, 1, duration, delay, cpuSamplingWorkIssueTime, CPU_SAMPLING_WORK_ID, maxFrames);

        profile[0] = (req) -> {
            recordProfile(req, hdr, profileEntries);
            return "Processed profile successfully!".getBytes(Charset.forName("UTF-8"));
        };
        profile[1] = (req) -> {
            profileCalledSecondTime.setTrue();
            return null;
        };
        return pollReqs;
    }

    private void assertPolledInStatusIsGood(PollReqWithTime[] pollReqs) {
        assertWorkStateAndResultIs(pollReqs[0].req.getWorkLastIssued(), 0, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        for (int i = 2; i < 4; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.pre_start, Recorder.WorkResponse.WorkResult.unknown, 0);
        }
        for (int i = 4; i < 14; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, i - 4);
        }
        assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 10);
        for (int i = 15; i < pollReqs.length; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), i + 99, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        }
    }

    private void assertPollIntervalIsGood(PollReqWithTime[] pollReqs, long prevTime) {
        long idx = 0;
        long previousTick;
        Matcher<Long> recorderTickMatcher = is(0l);
        for (PollReqWithTime prwt : pollReqs) {
            previousTick = assertRecorderInfoAllGood_AndGetTick(prwt.req.getRecorderInfo(), recorderTickMatcher, AssociationTest.rc(true));
            recorderTickMatcher = greaterThan(previousTick);
            if (idx > 0) {
                assertThat("idx = " + idx, prwt.time - prevTime, approximatelyBetween(970l, 2000l)); //~1 sec tolerance
            }
            prevTime = prwt.time;
            idx++;
        }
    }

    private void assertRecordingHeaderIsGood(String cpuSamplingWorkIssueTime, MutableObject<Recorder.RecordingHeader> hdr, final int maxFrames) {
        Recorder.Work w = Recorder.Work.newBuilder()
                .setWType(Recorder.WorkType.cpu_sample_work)
                .setCpuSample(Recorder.CpuSampleWork.newBuilder()
                        .setMaxFrames(maxFrames)
                        .setFrequency(CPU_SAMPLING_FREQ)
                        .build())
                .build();
        WorkHandlingTest.assertRecordingHeaderIsGood(hdr.getValue(), CONTROLLER_ID, CPU_SAMPLING_WORK_ID, cpuSamplingWorkIssueTime, 10, 2, 1, new Recorder.Work[]{w});
    }

    private void assertProfileCallAndContent(MutableBoolean profileCalledSecondTime, List<Recorder.Wse> profileEntries, Map<String, StackNodeMatcher> expectedContent, final PivotResolver pivotResolver, final HashMap<Integer, TraceInfo> traceInfoMap, final HashMap<Integer, ThreadInfo> thdInfoMap, final HashMap<Long, MthdInfo> mthdInfoMap, final HashMap<String, SampledStackNode> aggregations) {
        assertCpuProfileEntriesAre(profileEntries, expectedContent, false, pivotResolver, traceInfoMap, thdInfoMap, mthdInfoMap, aggregations);

        assertThat(profileCalledSecondTime.getValue(), is(false));
    }

    private static class TraceInfo {
        final String name;
        final int coverage;
        private final MergeSemantics mSem;
        private final boolean isGenerated;

        private static TraceInfo mergedTraceInfo(String name) {
            return new TraceInfo(name, 0, null, true);
        }

        private TraceInfo(String name, int coverage, MergeSemantics mSem) {
            this(name, coverage, mSem, false);
        }

        private TraceInfo(String name, int coverage, MergeSemantics mSem, boolean isGenerated) {
            this.name = name;
            this.coverage = coverage;
            this.mSem = mSem;
            this.isGenerated = isGenerated;
        }

        @Override
        public String toString() {
            return "TraceInfo{" +
                    "name='" + name + '\'' +
                    ", coverage=" + coverage +
                    ", mSem=" + mSem +
                    ", isGenerated=" + isGenerated +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TraceInfo traceInfo = (TraceInfo) o;

            if (coverage != traceInfo.coverage) return false;
            if (isGenerated != traceInfo.isGenerated) return false;
            if (name != null ? !name.equals(traceInfo.name) : traceInfo.name != null) return false;
            return mSem == traceInfo.mSem;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result + coverage;
            result = 31 * result + (mSem != null ? mSem.hashCode() : 0);
            return result;
        }
    }

    private static class ThreadInfo {
        final String name;
        final int priority;
        final boolean isDaemon;

        private ThreadInfo(String name, int priority, boolean isDaemon) {
            this.name = name;
            this.priority = priority;
            this.isDaemon = isDaemon;
        }

        @Override
        public String toString() {
            return "ThreadInfo{" +
                    "name='" + name + '\'' +
                    ", priority=" + priority +
                    ", isDaemon=" + isDaemon +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ThreadInfo that = (ThreadInfo) o;

            if (priority != that.priority) return false;
            if (isDaemon != that.isDaemon) return false;
            return name != null ? name.equals(that.name) : that.name == null;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result + priority;
            result = 31 * result + (isDaemon ? 1 : 0);
            return result;
        }
    }

    private static class MthdInfo {
        final String klass;
        final String name;
        final String sig;
        final String fileName;

        private MthdInfo(String klass, String name, String sig, String fileName) {
            this.klass = klass;
            this.name = name;
            this.sig = sig;
            this.fileName = fileName;
        }

        @Override
        public String toString() {
            return "MthdInfo{" +
                    "klass='" + klass + '\'' +
                    ", name='" + name + '\'' +
                    ", sig='" + sig + '\'' +
                    ", fileName='" + fileName + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MthdInfo mthdInfo = (MthdInfo) o;

            if (klass != null ? !klass.equals(mthdInfo.klass) : mthdInfo.klass != null) return false;
            if (name != null ? !name.equals(mthdInfo.name) : mthdInfo.name != null) return false;
            if (sig != null ? !sig.equals(mthdInfo.sig) : mthdInfo.sig != null) return false;
            return fileName != null ? fileName.equals(mthdInfo.fileName) : mthdInfo.fileName == null;
        }

        @Override
        public int hashCode() {
            int result = klass != null ? klass.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            result = 31 * result + (sig != null ? sig.hashCode() : 0);
            result = 31 * result + (fileName != null ? fileName.hashCode() : 0);
            return result;
        }
    }

    private void assertCpuProfileEntriesAre(List<Recorder.Wse> entries, Map<String, StackNodeMatcher> expected, final boolean ignoreOtherWseTypes, final PivotResolver pivotResolver, final Map<Integer, TraceInfo> traceInfoMap, final Map<Integer, ThreadInfo> thdInfoMap, final Map<Long, MthdInfo> mthdInfoMap, final Map<String, SampledStackNode> aggregations) {
        //first let us build the tree
        int totalSamples = makeTree(entries, ignoreOtherWseTypes, pivotResolver, traceInfoMap, thdInfoMap, mthdInfoMap, aggregations);

        //now let us match it
        for (Map.Entry<String, StackNodeMatcher> expectedEntry : expected.entrySet()) {
            SampledStackNode node = aggregations.get(expectedEntry.getKey());
            assertThat(node, notNullValue());
            assertTreesMatch(node, expectedEntry.getValue(), totalSamples);
        }
        assertThat(aggregations.size(), is(expected.size()));
    }

    private int makeTree(List<Recorder.Wse> entries, boolean ignoreOtherWseTypes, PivotResolver pivotResolver, Map<Integer, TraceInfo> traceInfoMap, Map<Integer, ThreadInfo> thdInfoMap, Map<Long, MthdInfo> mthdInfoMap, Map<String, SampledStackNode> aggregations) {
        int totalSamples = 0;
        for (Recorder.Wse entry : entries) {
            if (!ignoreOtherWseTypes) {
                assertThat(entry.getWType(), is(Recorder.WorkType.cpu_sample_work));
            } else if (entry.getWType() != Recorder.WorkType.cpu_sample_work) {
                continue;
            }
            assertThat(entry.hasCpuSampleEntry(), is(true));
            Recorder.IndexedData idxData = entry.getIndexedData();
            for (Recorder.ThreadInfo thdEntry : idxData.getThreadInfoList()) {
                int id = (int) thdEntry.getThreadId();
                assertThat(thdInfoMap.containsKey(id), is(false));
                String name = thdEntry.getThreadName();
                thdInfoMap.put(id, new ThreadInfo(name, thdEntry.getPriority(), thdEntry.getIsDaemon()));
                if (pivotResolver.aggregateByThd())
                    aggregations.put(wrap(name), makeRootNode());
            }

            for (Recorder.TraceContext traceEntry : idxData.getTraceCtxList()) {
                int id = traceEntry.getTraceId();
                assertThat(traceInfoMap.containsKey(id), is(false));
                String name = traceEntry.getTraceName();
                if (traceEntry.getIsGenerated()) {
                    traceInfoMap.put(id, TraceInfo.mergedTraceInfo(name));
                } else {
                    traceInfoMap.put(id, new TraceInfo(name, traceEntry.getCoveragePct(), translateMergeSemantic(traceEntry)));
                }

                if (pivotResolver.aggregateByCtx())
                    aggregations.put(wrap(name), makeRootNode());
            }
            for (Recorder.MethodInfo mthdEntry : idxData.getMethodInfoList()) {
                long id = mthdEntry.getMethodId();
                assertThat(mthdInfoMap.containsKey(id), is(false));
                mthdInfoMap.put(id, new MthdInfo(mthdEntry.getClassFqdn(), mthdEntry.getMethodName(), mthdEntry.getSignature(), mthdEntry.getFileName()));
            }
            Recorder.StackSampleWse e = entry.getCpuSampleEntry();
            for (int i = 0; i < e.getStackSampleCount(); i++) {
                Recorder.StackSample stackSample = e.getStackSample(i);
                for (Integer pivotId : pivotResolver.getAggregatingPivotIds(stackSample)) {
                    String name = pivotResolver.getAggregatingPivotName(traceInfoMap, thdInfoMap, pivotId);
                    SampledStackNode currentNode = aggregations.get(wrap(name));
                    for (int j = stackSample.getFrameCount(); j > 0; j--) {
                        Recorder.Frame frame = stackSample.getFrame(j - 1);
                        long methodId = frame.getMethodId();
                        int lineNo = frame.getLineNo();
                        int bci = frame.getBci();
                        MthdInfo mthdInfo = mthdInfoMap.get(methodId);
                        currentNode.onStackSampleCount++;
                        currentNode = currentNode.findOrCreateChild(mthdInfo, lineNo, bci);
                    }
                    currentNode.onCpuSampleCount++;
                    if (stackSample.getSnipped()) {
                        currentNode.onCpuSnippedCount++;
                    }
                    totalSamples++;
                }
            }
        }

        //debug aid, the thing is almost a json, just a little top-level key-massaging is necessary (massage and use 'jq')
        //System.out.println("aggregations = " + aggregations);
        return totalSamples;
    }

    private String wrap(String name) {
        return '"' + name + '"';
    }

    private MergeSemantics translateMergeSemantic(Recorder.TraceContext traceEntry) {
        Recorder.TraceContext.MergeSemantics merge = traceEntry.getMerge();
        for (MergeSemantics value : MergeSemantics.values()) {
            if (value.getTypeId() == merge.getNumber()) return value;
        }
        throw new IllegalStateException("No mapping merge-semantic type exists");
    }

    private SampledStackNode makeRootNode() {
        return new SampledStackNode(makeClassName(DUMMY_ROOT_NODE_CLASS_NAME), makeKlassFileName(DUMMY_ROOT_NODE_CLASS_NAME), DUMMY_ROOT_NODE_FN_NAME, DUMMY_ROOT_NOTE_FN_SIGNATURE, 0, 0, new HashSet<>(), 0, 0);
    }

    private static String makeClassName(final String dummyRootNodeClassName) {
        return "L" + dummyRootNodeClassName.replaceAll("\\.", "/") + ";";
    }

    private void assertTreesMatch(SampledStackNode node, StackNodeMatcher value, int totalSamples) {
        assertThat(node.file, containsString(makeKlassFileName(value.klassSimpleName)));
        assertThat(node.klass, is(value.klass));
        assertThat(node.fnName, is(value.fnName));
        assertThat(node.fnSig, is(value.fnSig));
        //TODO: uncomment me!!
        //assertThat(node.lineNo, is(value.lineNo));
        //assertThat(node.bci, is(value.bci));
        int childrenMatched = 0;
        int totalSamplesAccountedFor = 0;
        for (StackNodeMatcher child : value.children) {
            SampledStackNode matchingChild = node.findChildLike(child);
            childrenMatched++;
            assertThat(matchingChild, notNullValue());
            if (matchingChild.onCpuSampleCount > 0) {
                int actualSampleCount = matchingChild.onCpuSampleCount;
                totalSamplesAccountedFor += actualSampleCount;
                int expectedSamples = child.expectedOncpuPct * totalSamples / 100;
                int toleranceInSamples = child.pctMatchTolerance * totalSamples / 100;
                assertThat("Node onCpuSample PCT out of range for " + child, (long) actualSampleCount, approximately((long) expectedSamples, (long) toleranceInSamples));
            }
            assertTreesMatch(matchingChild, child, totalSamples);
        }
        int immediateChildrenSampleCount = node.immediateChildrenSampleCount();
        if (immediateChildrenSampleCount > 5) {
            assertThat((long) totalSamplesAccountedFor, approximately(immediateChildrenSampleCount));
        }
    }

    private static final class SampledStackNode {
        private final String klass;
        private final String file;
        private final String fnName;
        private final String fnSig;
        private final int lineNo;
        private int onCpuSampleCount;
        private final int bci;
        private final Map<SampledStackNode, SampledStackNode> children;
        private int onCpuSnippedCount = 0;
        public int onStackSampleCount;

        public SampledStackNode(String klass, String file, String fnName, String fnSig, int lineNo, int bci, Set<SampledStackNode> children, int onCpuSampleCount, final int onStackSampleCount) {
            this.klass = klass;
            this.file = file;
            this.fnName = fnName;
            this.fnSig = fnSig;
            this.lineNo = lineNo;
            this.onCpuSampleCount = onCpuSampleCount;
            this.onStackSampleCount = onStackSampleCount;
            this.bci = bci;
            this.children = new HashMap<>();
            for (SampledStackNode child : children) {
                this.children.put(child, child);
            }
        }

        public SampledStackNode(MthdInfo mthdInfo, int lineNo, int bci) {
            this(mthdInfo.klass, mthdInfo.fileName, mthdInfo.name, mthdInfo.sig, lineNo, bci, Collections.emptySet(), 0, 0);
        }

        @Override
        public String toString() {
            return "{\"type\": \"Sampled\"," +
                    "\"klass\":\"" + klass + "\"" +
                    ", \"file\":\"" + file + "\"" +
                    ", \"fnName\":\"" + fnName + "\"" +
                    ", \"fnSig\":\"" + fnSig + '\"' +
                    ", \"lineNo\":" + lineNo +
                    ", \"bci\":" + bci +
                    ", \"onCpuSampleCount\":" + onCpuSampleCount +
                    ", \"children\":" + childrenArray(children) +
                    ", \"onCpuSnippedCount\":" + onCpuSnippedCount +
                    '}';
        }

        private String childrenArray(Map<SampledStackNode, SampledStackNode> children) {
            StringBuilder b = new StringBuilder();
            b.append("[");
            for (SampledStackNode node : children.keySet()) {
                if (b.length() > 1) b.append(",");
                b.append(node);
            }
            b.append("]");
            return b.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SampledStackNode that = (SampledStackNode) o;

//            if (lineNo != that.lineNo) return false;
//            if (bci != that.bci) return false;
            if (klass != null ? !klass.equals(that.klass) : that.klass != null) return false;
            if (file != null ? !file.equals(that.file) : that.file != null) return false;
            if (fnName != null ? !fnName.equals(that.fnName) : that.fnName != null) return false;
            if (fnSig != null ? !fnSig.equals(that.fnSig) : that.fnSig != null) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int result = klass != null ? klass.hashCode() : 0;
            result = 31 * result + (file != null ? file.hashCode() : 0);
            result = 31 * result + (fnName != null ? fnName.hashCode() : 0);
            result = 31 * result + (fnSig != null ? fnSig.hashCode() : 0);
//            result = 31 * result + lineNo;
//            result = 31 * result + bci;
            return result;
        }

        public SampledStackNode findOrCreateChild(MthdInfo mthdInfo, int lineNo, int bci) {
            SampledStackNode node = new SampledStackNode(mthdInfo, lineNo, bci);
            SampledStackNode existingNode = children.get(node);
            if (existingNode == null) {
                children.put(node, node);
                return node;
            }
            return existingNode;
        }

        public SampledStackNode findChildLike(StackNodeMatcher like) {
            for (SampledStackNode child : children.keySet()) {
                if (child.klass.equals(like.klass) &&
                        child.fnName.equals(like.fnName) &&
                        child.fnSig.equals(like.fnSig)) {
                    return child;
                }
            }
            return null;
        }

        public Set<SampledStackNode> findChildrenLike(StackNodeMatcher like) {
            Set<SampledStackNode> matched = new HashSet<>();
            String targetKlassName = makeClassName(like.klass);
            for (SampledStackNode child : children.keySet()) {
                if (child.klass.equals(targetKlassName) &&
                        child.fnName.equals(like.fnName) &&
                        child.fnSig.equals(like.fnSig)) {
                    matched.add(child);
                }
            }
            return matched;
        }

        public int immediateChildrenSampleCount() {
            int count = 0;
            for (SampledStackNode stackNode : children.keySet()) {
                count += stackNode.onCpuSampleCount;
            }
            return count;
        }
    }

    private static final class StackNodeMatcher {
        private final String klassSimpleName;
        private final String klass;
        private final String fnName;
        private final String fnSig;
        private final int expectedOncpuPct;
        private final int pctMatchTolerance;
        private final Set<StackNodeMatcher> children;

        public StackNodeMatcher(Class klass, String fnName, String fnSig, int onCpuPct, int pctMatchTolerance, Set<StackNodeMatcher> children) {
            this(klass.getSimpleName(), klass.getCanonicalName(), fnName, fnSig, onCpuPct, pctMatchTolerance, children);
        }

        public StackNodeMatcher(String simpleName, String klass, String fnName, String fnSig, int onCpuPct, int pctMatchTolerance, Set<StackNodeMatcher> children) {
            this.klassSimpleName = simpleName;
            this.klass = makeClassName(klass);
            this.fnName = fnName;
            this.fnSig = fnSig;
            this.expectedOncpuPct = onCpuPct;
            this.pctMatchTolerance = pctMatchTolerance;
            this.children = children;
        }

        @Override
        public String toString() {
            return "{\"type\": \"Matcher\"," +
                    "\"klass\":\"" + klass + "\"" +
                    ", \"fnName\":\"" + fnName + "\"" +
                    ", \"fnSig\":\"" + fnSig + '\"' +
                    ", \"expectedOncpuPct\":" + expectedOncpuPct +
                    ", \"pctMatchTolerance\":" + pctMatchTolerance +
                    ", \"children\":" + children +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            StackNodeMatcher stackNodeMatcher = (StackNodeMatcher) o;

            if (expectedOncpuPct != stackNodeMatcher.expectedOncpuPct) return false;
            if (pctMatchTolerance != stackNodeMatcher.pctMatchTolerance) return false;
            if (!klass.equals(stackNodeMatcher.klass)) return false;
            if (!fnName.equals(stackNodeMatcher.fnName)) return false;
            if (!fnSig.equals(stackNodeMatcher.fnSig)) return false;
            return children.equals(stackNodeMatcher.children);
        }

        @Override
        public int hashCode() {
            int result = klass.hashCode();
            result = 31 * result + fnName.hashCode();
            result = 31 * result + fnSig.hashCode();
            return result;
        }
    }

    private static StackNodeMatcher nodeMatcher(Class klass, String fnName, final String fnSig, int expectedOncpuPct, int pctMatchTolerance, Set<StackNodeMatcher> children) {
        return new StackNodeMatcher(klass, fnName, fnSig, expectedOncpuPct, pctMatchTolerance, children);
    }

    private static StackNodeMatcher rootMatcher(String fnName, final String fnSig, int expectedOncpuPct, int pctMatchTolerance, Set<StackNodeMatcher> children) {
        return new StackNodeMatcher(DUMMY_ROOT_NODE_CLASS_NAME, DUMMY_ROOT_NODE_CLASS_NAME, fnName, fnSig, expectedOncpuPct, pctMatchTolerance, children);
    }

    private static Set<StackNodeMatcher> childrenMatcher(StackNodeMatcher... nodes) {
        Set<StackNodeMatcher> children = new HashSet<>();
        for (StackNodeMatcher node : nodes) {
            children.add(node);
        }
        return children;
    }

    public static interface PivotResolver {
        List<Integer> getAggregatingPivotIds(Recorder.StackSample stackSample);

        String getAggregatingPivotName(Map<Integer, TraceInfo> traceInfoMap, Map<Integer, ThreadInfo> thdInfoMap, Integer traceId);

        boolean aggregateByThd();

        boolean aggregateByCtx();
    }

    private class TraceIdPivotResolver implements PivotResolver {
        @Override
        public List<Integer> getAggregatingPivotIds(Recorder.StackSample stackSample) {
            return stackSample.getTraceIdList();
        }

        @Override
        public String getAggregatingPivotName(Map<Integer, TraceInfo> traceInfoMap, Map<Integer, ThreadInfo> thdInfoMap, Integer traceId) {
            return traceInfoMap.get(traceId).name;
        }

        @Override
        public boolean aggregateByThd() {
            return false;
        }

        @Override
        public boolean aggregateByCtx() {
            return true;
        }
    }

    private class ThreadIdPivotResolver implements PivotResolver {
        @Override
        public List<Integer> getAggregatingPivotIds(Recorder.StackSample stackSample) {
            List<Integer> ids = new ArrayList<>();
            if (stackSample.hasThreadId()) {
                ids.add((int) stackSample.getThreadId());//this is downward-cast a hack that is not meant for production
            }
            return ids;
        }

        @Override
        public String getAggregatingPivotName(Map<Integer, TraceInfo> traceInfoMap, Map<Integer, ThreadInfo> thdInfoMap, Integer thdId) {
            return thdInfoMap.get(thdId).name;
        }

        @Override
        public boolean aggregateByThd() {
            return true;
        }

        @Override
        public boolean aggregateByCtx() {
            return false;
        }
    }
}
