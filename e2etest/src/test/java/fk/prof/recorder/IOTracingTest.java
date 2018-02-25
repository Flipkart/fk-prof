package fk.prof.recorder;

import fk.prof.idl.Recorder;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;
import fk.prof.recorder.io.IOWorkload;
import fk.prof.recorder.utils.AgentRunner;
import fk.prof.recorder.utils.Matchers;
import fk.prof.recorder.utils.TestBackendServer;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableObject;
import org.hamcrest.Matcher;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static fk.prof.recorder.WorkHandlingTest.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

public class IOTracingTest {
    public static final int TRACING_THRESHOLD_MS = 0;
    public static final int MAX_FRAMES = 50;
    public static final int CONTROLLER_ID = 2;
    public static final int WORK_ID = 42;
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
            "poll_itvl=2," +
            "log_lvl=trace," +
            "capture_native_bt=y," +
            "stats_syslog_tag=foobar";
    private static final String DEFAULT_CTX_NAME = "~ OTHERS ~";
    private static final String UNKNOWN_CTX_NAME = "~ UNKNOWN ~";

    private TestBackendServer server;
    private Function<byte[], byte[]>[] association = new Function[2];
    private Function<byte[], byte[]>[] poll = new Function[9];
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
    public void testBasicFileIO() throws Exception {
        List<Recording.RecordingChunk> profileEntries = new ArrayList<>();
        MutableObject<Recording.RecordingHeader> hdr = new MutableObject<>();
        MutableBoolean profileCalledSecondTime = new MutableBoolean(false);
        String workIssueTime = ISODateTimeFormat.dateTime().print(DateTime.now());

        WorkHandlingTest.PollReqWithTime[] pollReqs =
                stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, workIssueTime, MAX_FRAMES);

        runner = new AgentRunner(IOWorkload.BasicFileStdOutWrite.class.getName(), USUAL_RECORDER_ARGS);
        runner.start(true);

        assocAction[0].get(4, TimeUnit.SECONDS);

        long prevTime = System.currentTimeMillis();

        assertThat(assocAction[0].isDone(), is(true));
        pollAction[poll.length - 1].get(poll.length * 2 + 5, TimeUnit.SECONDS); //some grace time

        profileAction[0].get(5, TimeUnit.SECONDS);

        // not matching capabilities. the work giver is making sure that we see correct capabilities.
        // work is only given if the capabilities match
        CpuSamplingTest.assertPollIntervalIsGood(pollReqs, prevTime, 1500l ,3000l,
                AssociationTest.rc(true, true), false);

        assertRecordingHeaderIsGood(workIssueTime, hdr, MAX_FRAMES);

        Profile p = process(profileEntries);

        assertThat(p.ioEvents.size(), Matchers.between(18, 22));
        assertThat(p.files.size(), is(2));

        int stdoutID = -1, fileId = -1;
        for(Map.Entry<Integer, Recording.FDInfo> file: p.files.entrySet()) {
            if("stdout".equals(file.getValue().getFileInfo().getFilename())) {
                stdoutID = file.getKey();
            }
            else {
                fileId = file.getKey();
            }
        }

        Matcher<Recording.IOTrace> matcher1 = new Matchers.IOTraceMatcher(p.methods, io(stdoutID, 512), ss1);
        Matcher<Recording.IOTrace> matcher2 = new Matchers.IOTraceMatcher(p.methods, io(fileId, 1024), ss2);

        // match content
        assertThat(p.ioEvents,
                everyItem(
                        anyOf(
                                matcher1,
                                matcher2
                        )));
    }

    private String[] ss1 = {
            "Ljava/io/FileOutputStream;::write",
            "Ljava/io/BufferedOutputStream;::write",
            "Ljava/io/PrintStream;::write",
            "Lsun/nio/cs/StreamEncoder;::writeBytes",
            "Lsun/nio/cs/StreamEncoder;::implFlushBuffer",
            "Lsun/nio/cs/StreamEncoder;::flushBuffer",
            "Ljava/io/OutputStreamWriter;::flushBuffer",
            "Ljava/io/PrintStream;::write",
            "Ljava/io/PrintStream;::print",
            "Lfk/prof/recorder/io/IOWorkload$BasicFileStdOutWrite;::main"
    };

    private String[] ss2 = {
            "Ljava/io/FileOutputStream;::write",
            "Lsun/nio/cs/StreamEncoder;::writeBytes",
            "Lsun/nio/cs/StreamEncoder;::implFlushBuffer",
            "Lsun/nio/cs/StreamEncoder;::implFlush",
            "Lsun/nio/cs/StreamEncoder;::flush",
            "Ljava/io/OutputStreamWriter;::flush",
            "Lfk/prof/recorder/io/IOWorkload$WriterThread;::run",
            "Ljava/lang/Thread;::run"
    };

    private Recording.IOTrace io(int fdid, int bytesRW) {
        return Recording.IOTrace.newBuilder()
                .setFdId(fdid)
                .setType(Recording.IOTraceType.file_write)
                .setWrite(Recording.FdWrite.newBuilder().setCount(bytesRW))
                .setTs(42)
                .setLatencyNs(42)
                .build();
    }

    private static Integer bytesRW(Recording.IOTrace trace) {
        switch (trace.getType()) {
            case socket_read:
            case file_read:
                return trace.getRead().getCount();
            case socket_write:
            case file_write:
                return trace.getWrite().getCount();
            default:
                throw new IllegalStateException("Unexpected io event type");
        }
    }

    static class Profile {
        public Map<Long, Recording.MethodInfo> methods = new HashMap<>();
        public Map<Integer, Recording.FDInfo> files = new HashMap<>();
        public Map<Long, Recording.ThreadInfo> threads = new HashMap<>();
        public List<Recording.IOTrace> ioEvents = new ArrayList<>();
        public List<String[]> ioStacktraces = new ArrayList<>();
    }

    Profile process(List<Recording.RecordingChunk> entries) {
        Profile p = new Profile();
        for (Recording.RecordingChunk entry : entries) {
            entry.getIndexedData().getMethodInfoList().stream().forEach(m -> p.methods.put(m.getMethodId(), m));
            entry.getIndexedData().getThreadInfoList().stream().forEach(t -> p.threads.put(t.getThreadId(), t));
            entry.getIndexedData().getFdInfoList().stream().forEach(f -> p.files.put(f.getId(), f));
            for(Recording.Wse wse :  entry.getWseList()) {
                if(wse.hasIoTraceEntry()) {
                    Recording.IOTraceWse io = wse.getIoTraceEntry();
                    for(Recording.IOTrace trace : io.getTracesList()) {
                        p.ioEvents.add(trace);

                        Recording.StackSample ss = trace.getStack();
                        String[] stacktrace = new String[ss.getFrameCount()];
                        for(int i = 0; i < trace.getStack().getFrameCount(); ++i) {
                            long mid = trace.getStack().getFrame(i).getMethodId();
                            stacktrace[i] = p.methods.get(mid).getClassFqdn() + "::" + p.methods.get(mid).getMethodName();
                        }
                        p.ioStacktraces.add(stacktrace);
                    }
                }
            }
        }

        return p;
    }

    private WorkHandlingTest.PollReqWithTime[] stubRecorderInteraction(List<Recording.RecordingChunk> profileEntries,
                                                                       MutableObject<Recording.RecordingHeader> hdr,
                                                                       MutableBoolean profileCalledSecondTime,
                                                                       String workIssueTime,
                                                                       final int maxFrames) {

        return stubRecorderInteraction(profileEntries, hdr, profileCalledSecondTime, workIssueTime, maxFrames,
                10, 2);
    }

    private WorkHandlingTest.PollReqWithTime[] stubRecorderInteraction(List<Recording.RecordingChunk> profileEntries,
                                                                       MutableObject<Recording.RecordingHeader> hdr,
                                                                       MutableBoolean profileCalledSecondTime,
                                                                       String workIssueTime,
                                                                       int maxFrames, int duration, int delay) {
        WorkHandlingTest.PollReqWithTime pollReqs[] = new WorkHandlingTest.PollReqWithTime[poll.length];

        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        association[0] = pointToAssociate(recInfo, 8090);

        MutableObject<Boolean> workIssuedFlag = new MutableObject<>(false);

        for (int i = 0; i < poll.length; i++) {
            poll[i] = issueIOTracingWork(pollReqs, i, duration, delay, workIssueTime, WORK_ID, maxFrames, workIssuedFlag);
        }

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

    public static Function<byte[], byte[]> issueIOTracingWork(PollReqWithTime[] pollReqs, int idx, final int duration, final int delay, final String issueTime, final int workId, final int maxFrames, MutableObject<Boolean> workIssued) {
        return cookPollResponse(pollReqs, idx, (nowString, builder) -> {
            boolean canTraceIO = pollReqs[idx].req.getRecorderInfo().getCapabilities().getCanTraceIo();

            if(canTraceIO && !workIssued.getValue()) {
                System.out.println(pollReqs[idx].req.getRecorderInfo().toString());
                WorkEntities.WorkAssignment.Builder workAssignmentBuilder = prepareWorkAssignment(nowString, builder, delay, duration, "io tracing work", workId);
                WorkEntities.Work.Builder workBuilder = workAssignmentBuilder.addWorkBuilder();
                workBuilder.setWType(WorkEntities.WorkType.io_trace_work)
                        .setIoTrace(WorkEntities.IOTraceWork.newBuilder().setLatencyThresholdMs(0).setMaxFrames(maxFrames).build());
                workIssued.setValue(true);
            }
            else {
                prepareWorkAssignment(nowString, builder, 0, 0, "no work for ya!", idx + 100);
            }
        }, issueTime);
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

    private void assertRecordingHeaderIsGood(String workIssueTime, MutableObject<Recording.RecordingHeader> hdr,
                                             final int maxFrames) {
        WorkEntities.Work w = WorkEntities.Work.newBuilder()
                .setWType(WorkEntities.WorkType.io_trace_work)
                .setIoTrace(WorkEntities.IOTraceWork.newBuilder()
                        .setMaxFrames(maxFrames)
                        .setLatencyThresholdMs(TRACING_THRESHOLD_MS)
                        .build())
                .build();
        WorkHandlingTest.assertRecordingHeaderIsGood(hdr.getValue(), CONTROLLER_ID, WORK_ID, workIssueTime, 10, 2, 1, new WorkEntities.Work[]{w});
    }
}

