package fk.prof.recorder;

import com.google.protobuf.CodedInputStream;
import fk.prof.recorder.main.Burn20And80PctCpu;
import fk.prof.recorder.utils.AgentRunner;
import fk.prof.recorder.utils.TestBackendServer;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableObject;
import org.hamcrest.Matcher;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import recording.Recorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.zip.Adler32;

import static fk.prof.recorder.AssociationTest.rc;
import static fk.prof.recorder.utils.Matchers.approximately;
import static fk.prof.recorder.utils.Matchers.approximatelyBetween;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.collection.IsArray.array;
import static org.junit.Assert.assertThat;

public class WorkHandlingTest {
    public static final String CPU_SAMPLING_WORK_DESCRIPTION = "ok, let us capture some on-cpu stack-samples, shall we?";
    public static final int CPU_SAMPLING_FREQ = 100;
    public static final int CPU_SAMPLING_MAX_FRAMES = 50;
    public static final int CONTROLLER_ID = 2;
    public static final int CPU_SAMPLING_WORK_ID = 42;
    private static final String DEFAULT_ARGS = "service_endpoint=http://127.0.0.1:8080," +
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
        setRunner(DEFAULT_ARGS);
    }

    private void setRunner(final String args) {
        runner = new AgentRunner(Burn20And80PctCpu.class.getCanonicalName(), args);
    }

    private void wireUpProfileAction(final boolean waitForReqBytes) {
        profileAction = associateServer.register("/profile", profile, waitForReqBytes);
    }

    @After
    public void tearDown() {
        runner.stop();
        server.stop();
        associateServer.stop();
    }

    public static class PollReqWithTime {
        public PollReqWithTime(Recorder.PollReq req) {
            this.req = req;
            time = System.currentTimeMillis();
        }

        final Recorder.PollReq req;
        final long time;
    }

    @Test
    public void should_TrackAssignedWork() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        association[0] = pointToAssociate(recInfo, 8090);
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];
        for (int i = 0; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }

        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        long idx = 0;
        Matcher<Long> recorderTickMatcher = is(0l);
        long previousTick;
        for (PollReqWithTime prwt : pollReqs) {
            previousTick = AssociationTest.assertRecorderInfoAllGood_AndGetTick(prwt.req.getRecorderInfo(), recorderTickMatcher, rc(true));
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
    public void should_NotAcceptNewWork_WhenSomeWorkInAlreadyUnderway() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        MutableBoolean profileCalled = new MutableBoolean(false);
        association[0] = pointToAssociate(recInfo, 8090);
        wireUpProfileAction(false);
        profile[0] = (req) -> {
            profileCalled.setTrue();
            return null;
        };
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];
        poll[0] = tellRecorderWeHaveNoWork(pollReqs, 0);
        poll[1] = tellRecorderWeHaveNoWork(pollReqs, 1, 10, 2);
        for (int i = 2; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }

        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        long idx = 0;
        Matcher<Long> recorderTickMatcher = is(0l);
        long previousTick;
        for (PollReqWithTime prwt : pollReqs) {
            previousTick = AssociationTest.assertRecorderInfoAllGood_AndGetTick(prwt.req.getRecorderInfo(), recorderTickMatcher, rc(true));
            recorderTickMatcher = greaterThan(previousTick);
            if (idx > 0) {
                assertThat(prwt.time - prevTime, approximatelyBetween(1000l, 2000l)); //~1 sec tolerance
            }
            prevTime = prwt.time;
            idx++;
        }

        assertWorkStateAndResultIs(pollReqs[0].req.getWorkLastIssued(), 0, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        for (int i = 2; i < 4; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), 101, Recorder.WorkResponse.WorkState.pre_start, Recorder.WorkResponse.WorkResult.unknown, 0);
        }
        for (int i = 4; i < 13; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), 101, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, i - 4);
        }
        if (pollReqs[13].req.getWorkLastIssued().getWorkState() == Recorder.WorkResponse.WorkState.complete) {//if it finished just before this poll
            assertWorkStateAndResultIs(pollReqs[13].req.getWorkLastIssued(), 101, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 9);
            assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), 113, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        } else {//if it finished just after this poll
            assertWorkStateAndResultIs(pollReqs[13].req.getWorkLastIssued(), 101, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, 9);
            assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), 101, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 10);
        }
        for (int i = 15; i < pollReqs.length; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), i + 99, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        }
        assertThat(profileCalled.getValue(), is(false));
    }

    @Test
    public void should_Track_And_Retire_CpuProfileWork() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        association[0] = pointToAssociate(recInfo, 8090);
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];
        poll[0] = tellRecorderWeHaveNoWork(pollReqs, 0);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());
        poll[1] = issueCpuProfilingWork(pollReqs, 1, 10, 2, cpuSamplingWorkIssueTime, CPU_SAMPLING_WORK_ID, CPU_SAMPLING_MAX_FRAMES);
        for (int i = 2; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();
        List<Recorder.Wse> profileEntries = new ArrayList<>();

        wireUpProfileAction(true);

        profile[0] = (req) -> {
            recordProfile(req, hdr, profileEntries);
            return new byte[0];
        };
        profile[1] = (req) -> {
            profileCalledSecondTime.setTrue();
            return null;
        };

        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollingWasAllGood(pollReqs, prevTime, rc(true));

        assertWorkStateAndResultIs(pollReqs[0].req.getWorkLastIssued(), 0, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        for (int i = 2; i < 4; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.pre_start, Recorder.WorkResponse.WorkResult.unknown, 0);
        }
        for (int i = 4; i < 13; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, i - 4);
        }
        if (pollReqs[13].req.getWorkLastIssued().getWorkState() == Recorder.WorkResponse.WorkState.complete) {//if it finished just before this poll
            assertWorkStateAndResultIs(pollReqs[13].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 9);
            assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), 113, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        } else {//if it finished just after this poll
            assertWorkStateAndResultIs(pollReqs[13].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, 9);
            assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 10);
        }

        assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 10);
        for (int i = 15; i < pollReqs.length; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), i + 99, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        }

        Recorder.Work w = Recorder.Work.newBuilder()
                .setWType(Recorder.WorkType.cpu_sample_work)
                .setCpuSample(Recorder.CpuSampleWork.newBuilder()
                        .setMaxFrames(CPU_SAMPLING_MAX_FRAMES)
                        .setFrequency(CPU_SAMPLING_FREQ)
                        .build())
                .build();
        assertRecordingHeaderIsGood(hdr.getValue(), CONTROLLER_ID, CPU_SAMPLING_WORK_ID, cpuSamplingWorkIssueTime, 10, 2, 1, new Recorder.Work[]{w});

        assertThat(profileCalledSecondTime.getValue(), is(false));
    }

    private void assertPollingWasAllGood(PollReqWithTime[] pollReqs, long prevTime, final Recorder.RecorderCapabilities rc) {
        long idx = 0;
        Matcher<Long> recorderTickMatcher = is(0l);
        long previousTick;
        for (PollReqWithTime prwt : pollReqs) {
            previousTick = AssociationTest.assertRecorderInfoAllGood_AndGetTick(prwt.req.getRecorderInfo(), recorderTickMatcher, rc);
            recorderTickMatcher = greaterThan(previousTick);
            if (idx > 0) {
                assertThat("idx = " + idx, prwt.time - prevTime, approximatelyBetween(970l, 2000l)); //~1 sec tolerance
            }
            prevTime = prwt.time;
            idx++;
        }
    }

    @Test
    public void should_Abort_CpuProfileWork_When_Profile_Upload_Fails() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        Boolean[] profileCalled = {false, false};
        association[0] = pointToAssociate(recInfo, 8090);
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];
        poll[0] = tellRecorderWeHaveNoWork(pollReqs, 0);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());
        poll[1] = issueCpuProfilingWork(pollReqs, 1, 10, 2, cpuSamplingWorkIssueTime, CPU_SAMPLING_WORK_ID, CPU_SAMPLING_MAX_FRAMES);
        for (int i = 2; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();

        wireUpProfileAction(false);
        profile[0] = (req) -> {
            profileCalled[0] = true;
            throw new RuntimeException("Ouch! something went wrong.");
        };
        profile[1] = (req) -> {
            profileCalled[1] = true;
            return null;
        };

        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollingWasAllGood(pollReqs, prevTime, rc(true));

        assertWorkStateAndResultIs(pollReqs[0].req.getWorkLastIssued(), 0, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        for (int i = 2; i < 4; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.pre_start, Recorder.WorkResponse.WorkResult.unknown, 0);
        }
        assertWorkStateAndResultIs(pollReqs[4].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.failure, 0);
        for (int i = 5; i < pollReqs.length; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), i + 99, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        }

        assertThat(profileCalled, is(array(equalTo(true), equalTo(false))));
    }

    @Test
    public void should_Abort_CpuProfileWork_When_Profile_Upload_Appears_Too_Slow() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        Boolean[] profileCalled = {false, false};
        association[0] = pointToAssociate(recInfo, 8090);
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];
        poll[0] = tellRecorderWeHaveNoWork(pollReqs, 0);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());
        poll[1] = issueCpuProfilingWork(pollReqs, 1, 10, 2, cpuSamplingWorkIssueTime, CPU_SAMPLING_WORK_ID, CPU_SAMPLING_MAX_FRAMES);
        for (int i = 2; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();

        wireUpProfileAction(false);
        profile[0] = (req) -> {
            profileCalled[0] = true;
            try {
                Thread.sleep((poll.length + 2) * 1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return new byte[0];
        };
        profile[1] = (req) -> {
            profileCalled[1] = true;
            return null;
        };

        setRunner(DEFAULT_ARGS + ",tx_ring_sz=10,slow_tx_tolerance=1.01");
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollingWasAllGood(pollReqs, prevTime, rc(true));

        assertWorkStateAndResultIs(pollReqs[0].req.getWorkLastIssued(), 0, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        for (int i = 2; i < 4; i++) {
            assertWorkStateAndResultIs("i = " + i, pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.pre_start, Recorder.WorkResponse.WorkResult.unknown, 0);
        }
        for (int i = 4; i < 14; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.running, Recorder.WorkResponse.WorkResult.unknown, i - 4);
        }
        assertWorkStateAndResultIs(pollReqs[14].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.failure, 10);
        for (int i = 15; i < pollReqs.length; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), i + 99, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        }

        assertThat(profileCalled, is(array(equalTo(true), equalTo(false))));
    }

    @Test
    public void should_Fail_CpuProfileWork_When_SigprofNotAllowed() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        Boolean[] profileCalled = {false};
        association[0] = pointToAssociate(recInfo, 8090);
        PollReqWithTime pollReqs[] = new PollReqWithTime[poll.length];
        poll[0] = tellRecorderWeHaveNoWork(pollReqs, 0);
        String cpuSamplingWorkIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());
        poll[1] = issueCpuProfilingWork(pollReqs, 1, 10, 2, cpuSamplingWorkIssueTime, CPU_SAMPLING_WORK_ID, CPU_SAMPLING_MAX_FRAMES);
        for (int i = 2; i < poll.length; i++) {
            poll[i] = tellRecorderWeHaveNoWork(pollReqs, i);
        }
        MutableObject<Recorder.RecordingHeader> hdr = new MutableObject<>();

        wireUpProfileAction(false);
        profile[0] = (req) -> {
            profileCalled[0] = true;
            throw new RuntimeException("Ouch! something went wrong.");
        };

        setRunner(DEFAULT_ARGS + ",allow_sigprof=n");
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);
        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length + 4, TimeUnit.SECONDS); //some grace time

        assertPollingWasAllGood(pollReqs, prevTime, rc(false));

        assertWorkStateAndResultIs(pollReqs[0].req.getWorkLastIssued(), 0, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[1].req.getWorkLastIssued(), 100, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        assertWorkStateAndResultIs(pollReqs[2].req.getWorkLastIssued(), CPU_SAMPLING_WORK_ID, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.failure, 0);
        for (int i = 3; i < pollReqs.length; i++) {
            assertWorkStateAndResultIs(pollReqs[i].req.getWorkLastIssued(), i + 99, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
        }

        assertThat(profileCalled, is(array(equalTo(false))));
    }

    public static void assertRecordingHeaderIsGood(Recorder.RecordingHeader rh, final int controllerId, final long workId, String cpuSamplingWorkIssueTime, final int duration, final int delay, final int workCount, final Recorder.Work[] expectedWork) {
        assertThat(rh, notNullValue());
        assertThat(rh.getRecorderVersion(), is(Versions.RECORDER_VERSION));
        assertThat(rh.getControllerVersion(), is(Versions.CONTROLLER_VERSION));
        assertThat(rh.getControllerId(), is(controllerId));
        Recorder.WorkAssignment wa = rh.getWorkAssignment();
        assertThat(wa.getWorkId(), is(workId));
        assertThat(wa.getIssueTime(), is(cpuSamplingWorkIssueTime));
        assertThat(wa.getDuration(), is(duration));
        assertThat(wa.getDelay(), is(delay));
        assertThat(wa.getWorkCount(), is(expectedWork.length));
        for (int i = 0; i < expectedWork.length; i++) {
            Recorder.Work w = wa.getWork(i);
            assertThat(w, is(expectedWork[i]));
        }
    }

    public static void recordProfile(byte[] req, MutableObject<Recorder.RecordingHeader> hdr, List<Recorder.Wse> entries) {
        try {
            recordProfile_(req, hdr, entries);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void recordProfile_(byte[] req, MutableObject<Recorder.RecordingHeader> hdr, List<Recorder.Wse> entries) throws IOException {
        CodedInputStream is = CodedInputStream.newInstance(req);
        assertThat(is.readUInt32(), is(Versions.RECORDER_ENCODING_VERSION));

        int headerLen = is.readUInt32();

        int hdrLimit = is.pushLimit(headerLen);
        Recorder.RecordingHeader.Builder rhBuilder = Recorder.RecordingHeader.newBuilder();
        rhBuilder.mergeFrom(is);
        is.popLimit(hdrLimit);

        Recorder.RecordingHeader rh = rhBuilder.build();
        hdr.setValue(rh);
        //// Hdr len and chksum
        int bytesBeforeChksum = is.getTotalBytesRead();
        int chksum = is.readUInt32();
        int bytesAfterChksum = is.getTotalBytesRead();
        ///////////////////////

        Adler32 csum = new Adler32();
        csum.reset();
        csum.update(req, 0, bytesBeforeChksum);
        assertThat((int) csum.getValue(), is(chksum));

        while (true) {
            int wseLen = is.readUInt32();
            if (wseLen == 0) break; //EOF condition
            if (bytesAfterChksum >= req.length)
                throw new IllegalStateException("Stream ended before recorder EoF-marker");
            int wseLim = is.pushLimit(wseLen);
            Recorder.Wse.Builder wseBuilder = Recorder.Wse.newBuilder();
            wseBuilder.mergeFrom(is);
            is.popLimit(wseLim);
            entries.add(wseBuilder.build());

            //// len and chksum
            bytesBeforeChksum = is.getTotalBytesRead() - bytesAfterChksum;
            csum.reset();
            csum.update(req, bytesAfterChksum, bytesBeforeChksum);
            int wseChksum = is.readUInt32();
            assertThat(wseChksum, is((int) csum.getValue()));
            bytesAfterChksum = is.getTotalBytesRead();
            ///////////////////////
        }
    }

    public static void assertItHadNoWork(Recorder.WorkResponse workLastIssued, long expectedId) {
        assertWorkStateAndResultIs(workLastIssued, expectedId, Recorder.WorkResponse.WorkState.complete, Recorder.WorkResponse.WorkResult.success, 0);
    }

    public static void assertWorkStateAndResultIs(String ctx, Recorder.WorkResponse workLastIssued, long expectedId, final Recorder.WorkResponse.WorkState state, final Recorder.WorkResponse.WorkResult result, final int elapsedTime) {
        assertThat(ctx, workLastIssued.getWorkId(), is(expectedId));
        assertThat(ctx, workLastIssued.getWorkResult(), is(result));
        assertThat(ctx, workLastIssued.getWorkState(), is(state));
        assertThat(ctx, (long) workLastIssued.getElapsedTime(), approximately(elapsedTime, 1));
    }

    public static void assertWorkStateAndResultIs(Recorder.WorkResponse workLastIssued, long expectedId, final Recorder.WorkResponse.WorkState state, final Recorder.WorkResponse.WorkResult result, final int elapsedTime) {
        assertWorkStateAndResultIs("UNKNOWN", workLastIssued, expectedId, state, result, elapsedTime);
    }

    public static Function<byte[], byte[]> issueCpuProfilingWork(PollReqWithTime[] pollReqs, int idx, final int duration, final int delay, final String issueTime, final int workId, final int cpuSamplingMaxFrames) {
        return cookPollResponse(pollReqs, idx, (nowString, builder) -> {
            Recorder.WorkAssignment.Builder workAssignmentBuilder = prepareWorkAssignment(nowString, builder, delay, duration, CPU_SAMPLING_WORK_DESCRIPTION, workId);
            Recorder.Work.Builder workBuilder = workAssignmentBuilder.addWorkBuilder();
            workBuilder.setWType(Recorder.WorkType.cpu_sample_work).getCpuSampleBuilder()
                    .setFrequency(CPU_SAMPLING_FREQ)
                    .setMaxFrames(cpuSamplingMaxFrames);
        }, issueTime);
    }

    public static Recorder.WorkAssignment.Builder prepareWorkAssignment(String nowString, Recorder.PollRes.Builder builder, int delay, int duration, final String desc, int workId) {
        return builder.getAssignmentBuilder()
                .setWorkId(workId)
                .setDelay(delay)
                .setDuration(duration)
                .setIssueTime(nowString)
                .setDescription(desc);
    }

    public static Function<byte[], byte[]> tellRecorderToContinueWhatItWasDoing(PollReqWithTime[] pollReqs, int idx) {
        return cookPollResponse(pollReqs, idx, (nowString, builder) -> {
        }, ISODateTimeFormat.dateTime().print(DateTime.now()));
    }

    public static Function<byte[], byte[]> tellRecorderWeHaveNoWork(PollReqWithTime[] pollReqs, int idx) {
        return tellRecorderWeHaveNoWork(pollReqs, idx, 0, 0);
    }

    public static Function<byte[], byte[]> tellRecorderWeHaveNoWork(PollReqWithTime[] pollReqs, int idx, int duration, final int delay) {
        return cookPollResponse(pollReqs, idx, (nowString, builder) -> {
            prepareWorkAssignment(nowString, builder, delay, duration, "no work for ya!", idx + 100);
        }, ISODateTimeFormat.dateTime().print(DateTime.now()));
    }

    public static Function<byte[], byte[]> cookPollResponse(PollReqWithTime[] pollReqs, int idx, final BiConsumer<String, Recorder.PollRes.Builder> biConsumer, final String nowString) {
        return (req) -> {
            try {
                Recorder.PollReq.Builder pollReqBuilder = Recorder.PollReq.newBuilder();
                pollReqs[idx] = new PollReqWithTime(pollReqBuilder.mergeFrom(req).build());

                Recorder.PollRes.Builder builder = Recorder.PollRes.newBuilder()
                        .setLocalTime(nowString)
                        .setControllerId(CONTROLLER_ID)
                        .setControllerVersion(Versions.CONTROLLER_VERSION);
                biConsumer.accept(nowString, builder);
                Recorder.PollRes pollRes = builder.build();
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                pollRes.writeTo(os);
                return os.toByteArray();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        };
    }

    public static Function<byte[], byte[]> pointToAssociate(MutableObject<Recorder.RecorderInfo> recInfo, final int associatePort) {
        return (req) -> {
            try {
                Recorder.RecorderInfo.Builder recInfoBuilder = Recorder.RecorderInfo.newBuilder();
                recInfo.setValue(recInfoBuilder.mergeFrom(req).build());
                Recorder.AssignedBackend assignedBackend = Recorder.AssignedBackend.newBuilder()
                        .setHost("127.0.0.15")
                        .setPort(associatePort)
                        .build();

                ByteArrayOutputStream os = new ByteArrayOutputStream();
                assignedBackend.writeTo(os);
                return os.toByteArray();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        };
    }
}
