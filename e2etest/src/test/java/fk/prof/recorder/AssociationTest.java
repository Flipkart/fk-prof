package fk.prof.recorder;

import fk.prof.recorder.main.SleepForever;
import fk.prof.recorder.utils.AgentRunner;
import fk.prof.recorder.utils.Matchers;
import fk.prof.recorder.utils.TestBackendServer;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableLong;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static java.lang.System.currentTimeMillis;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class AssociationTest {
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
            "log_lvl=trace," +
            "stats_syslog_tag=foobar";
    private TestBackendServer server;
    private Function<byte[], byte[]>[] association = new Function[10];
    private Function<byte[], byte[]>[] poll = new Function[10];
    private Function<byte[], byte[]>[] poll2 = new Function[10];
    private Future[] assocAction;
    private Future[] pollAction;
    private AgentRunner runner;
    private TestBackendServer associateServer;
    private TestBackendServer associateServer2;
    private Future[] pollAction2;

    @Before
    public void setUp() {
        server = new TestBackendServer(8080);
        associateServer = new TestBackendServer(8090);
        associateServer2 = new TestBackendServer(8091);
        assocAction = server.register("/association", association);
        pollAction = associateServer.register("/poll", poll);
        pollAction2 = associateServer2.register("/poll", poll2);
        setupRunner(DEFAULT_ARGS);
    }

    private void setupRunner(final String args) {
        runner = new AgentRunner(SleepForever.class.getCanonicalName(), args);
    }

    @After
    public void tearDown() {
        runner.stop();
        server.stop();
        associateServer.stop();
        associateServer2.stop();
    }

    @Test
    public void should_DiscoverAssociate_and_SayHelloToIt() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        MutableBoolean assocCalledMoreThanOnce = new MutableBoolean(false);
        association[0] = pointToAssociate(recInfo, 8090);
        association[1] = (req) -> {
            assocCalledMoreThanOnce.setValue(true);
            throw new IllegalStateException("Unexpected move by recorder... Time to chicken out!");
        };
        MutableObject<Recorder.PollReq> pollReq = new MutableObject<>();
        poll[0] = tellRecorderWeHaveNoWork(pollReq);
        MutableBoolean pollCalledMoreThanOnce = new MutableBoolean(false);
        poll[1] = (req) -> {
            pollCalledMoreThanOnce.setValue(true);
            return new byte[0];
        };

        setupRunner(DEFAULT_ARGS + ",allow_sigprof=n");
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);

        assertThat(assocAction[0].isDone(), is(true));
        assertRecorderInfoAllGood_AndGetTick(recInfo.getValue(), is(0l), rc(false));

        pollAction[0].get(4, TimeUnit.SECONDS);

        Recorder.PollReq pollRequest = pollReq.getValue();
        assertRecorderInfoAllGood_AndGetTick(pollRequest.getRecorderInfo(), is(0l), rc(false));
        Recorder.WorkResponse workLastIssued = pollReq.getValue().getWorkLastIssued();
        assertReportedBootstrapWorkCompletion(workLastIssued);

        assertThat(pollCalledMoreThanOnce.getValue(), is(false));
        assertThat(assocCalledMoreThanOnce.getValue(), is(false));
    }

    @Test(timeout = 10000)
    public void should_DieCleanly_InAbsenceOfAssociate() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        associateServer.stop();
        associateServer2.stop();
        setupRunner(DEFAULT_ARGS + ",allow_sigprof=n");
        runner.start();

        Thread.sleep(4000);

        assertThat(runner.stop(), is(true));//this actually waits for the process to be reaped
        assertThat(runner.exitCode(), is(128 + 15)); //15 == SIGTERM
    }

    @Test
    public void should_RetryPoll_WhenAssociateHasInternalProblems() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        association[0] = pointToAssociate(recInfo, 8090);
        MutableBoolean assocCalledMoreThanOnce = new MutableBoolean(false);
        association[1] = (req) -> {
            assocCalledMoreThanOnce.setValue(true);
            throw new IllegalStateException("Unexpected move by recorder... Time to chicken out!");
        };
        MutableObject<Recorder.PollReq> pollReq = new MutableObject<>();
        long[] pollCalledAt = {0l, 0l, 0l};
        poll[0] = (req) -> {
            pollCalledAt[0] = currentTimeMillis();
            throw new IllegalStateException("Something is temporarily wrong!");
        };
        poll[1] = (req) -> {
            pollCalledAt[1] = currentTimeMillis();
            throw new IllegalStateException("Something is still wrong (temporarily, of-course)!");
        };
        poll[2] = (req) -> {
            pollCalledAt[2] = currentTimeMillis();
            return tellRecorderWeHaveNoWork(pollReq).apply(req);
        };

        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);

        assertThat(assocAction[0].isDone(), is(true));
        assertRecorderInfoAllGood_AndGetTick(recInfo.getValue(), is(0l), rc(true));

        pollAction[2].get(8, TimeUnit.SECONDS);

        Recorder.PollReq pollRequest = pollReq.getValue();
        assertThat(pollRequest, is(notNullValue()));
        assertRecorderInfoAllGood_AndGetTick(pollRequest.getRecorderInfo(), is(0l), rc(true));
        Recorder.WorkResponse workLastIssued = pollReq.getValue().getWorkLastIssued();
        assertReportedBootstrapWorkCompletion(workLastIssued);

        assertThat(pollCalledAt[1] - pollCalledAt[0], is(Matchers.approximately(2000l)));
        assertThat(pollCalledAt[2] - pollCalledAt[1], is(Matchers.approximately(4000l)));
        assertThat(assocCalledMoreThanOnce.getValue(), is(false));
    }

    @Test
    public void should_RequestNewAssociate_WhenAssignedOneRefusesToWork() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        MutableObject<Recorder.RecorderInfo> recInfo1 = new MutableObject<>();
        association[0] = pointToAssociate(recInfo, 8090);
        association[1] = pointToAssociate(recInfo1, 8091);
        MutableBoolean assocCalledMoreThanTwice = new MutableBoolean(false);
        association[2] = (req) -> {
            assocCalledMoreThanTwice.setValue(true);
            throw new IllegalStateException("Unexpected move by recorder... Time to chicken out!");
        };
        MutableObject<Recorder.PollReq> pollReq = new MutableObject<>();
        long[] pollCalledAt = {0l, 0l, 0l, 0l};
        poll[0] = (req) -> {
            pollCalledAt[0] = currentTimeMillis();
            throw new IllegalStateException("Something is temporarily wrong!");
        };
        poll[1] = (req) -> {
            pollCalledAt[1] = currentTimeMillis();
            throw new IllegalStateException("Something is still wrong (temporarily, of-course)!");
        };
        poll[2] = (req) -> {
            pollCalledAt[2] = currentTimeMillis();
            throw new IllegalStateException("Something is still wrong (temporarily, of-course)!");
        };

        poll2[0] = (req) -> {
            pollCalledAt[3] = currentTimeMillis();
            return tellRecorderWeHaveNoWork(pollReq).apply(req);
        };

        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);

        assertThat(assocAction[0].isDone(), is(true));
        assertRecorderInfoAllGood_AndGetTick(recInfo.getValue(), is(0l), rc(true));
        assertRecorderInfoAllGood_AndGetTick(recInfo.getValue(), is(0l), rc(true));

        pollAction2[0].get(120, TimeUnit.SECONDS);

        Recorder.PollReq pollRequest = pollReq.getValue();
        assertThat(pollRequest, is(notNullValue()));
        assertRecorderInfoAllGood_AndGetTick(pollRequest.getRecorderInfo(), is(0l), rc(true));
        Recorder.WorkResponse workLastIssued = pollReq.getValue().getWorkLastIssued();
        assertReportedBootstrapWorkCompletion(workLastIssued);

        assertThat(pollCalledAt[1] - pollCalledAt[0], is(Matchers.approximately(2000l)));
        assertThat(pollCalledAt[2] - pollCalledAt[1], is(Matchers.approximately(4000l)));
        assertThat(assocCalledMoreThanTwice.getValue(), is(false));
    }

    @Test
    public void should_RequestNewAssociate_WhenAssignedOneIsUnreachable() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        associateServer.stop();
        association[0] = pointToAssociate(recInfo, 8090);
        association[1] = pointToAssociate(recInfo, 8091);
        MutableBoolean assocCalledMoreThanTwice = new MutableBoolean(false);
        association[2] = (req) -> {
            assocCalledMoreThanTwice.setValue(true);
            throw new IllegalStateException("Unexpected move by recorder... Time to chicken out!");
        };
        MutableObject<Recorder.PollReq> pollReq = new MutableObject<>();
        MutableLong associate2PolledAt = new MutableLong();

        poll2[0] = (req) -> {
            associate2PolledAt.setValue(currentTimeMillis());
            return tellRecorderWeHaveNoWork(pollReq).apply(req);
        };

        long startTime = currentTimeMillis();
        runner.start();

        assocAction[0].get(4, TimeUnit.SECONDS);

        assertThat(assocAction[0].isDone(), is(true));
        assertRecorderInfoAllGood_AndGetTick(recInfo.getValue(), is(0l), rc(true));

        pollAction2[0].get(120, TimeUnit.SECONDS);

        Recorder.PollReq pollRequest = pollReq.getValue();
        assertThat(pollRequest, is(notNullValue()));
        assertRecorderInfoAllGood_AndGetTick(pollRequest.getRecorderInfo(), is(0l), rc(true));
        Recorder.WorkResponse workLastIssued = pollReq.getValue().getWorkLastIssued();
        assertReportedBootstrapWorkCompletion(workLastIssued);

        assertThat(associate2PolledAt.getValue() - startTime, is(greaterThan(14l)));
        assertThat(assocCalledMoreThanTwice.getValue(), is(false));
    }

    @Test
    public void should_Continue_TryingToDiscoverAssociate_WhenServiceEndpointIsUnreachable() throws ExecutionException, InterruptedException, IOException, TimeoutException {
        MutableObject<Recorder.RecorderInfo> recInfo = new MutableObject<>();
        server.stop();
        long[] associateDiscoveryCalledAt = {0l, 0l, 0l, 0l};
        
        association[0] = (req) -> {
            associateDiscoveryCalledAt[0] = currentTimeMillis();
            throw new IllegalStateException("Something went wrong!");
        };
        
        association[1] = (req) -> {
            associateDiscoveryCalledAt[1] = currentTimeMillis();
            throw new IllegalStateException("Something went wrong, once again!");
        };
        
        association[2] = (req) -> {
            associateDiscoveryCalledAt[2] = currentTimeMillis();
            return pointToAssociate(recInfo, 8090).apply(req);
        };
        MutableBoolean assocCalledMoreThanThrice = new MutableBoolean(false);
        
        association[3] = (req) -> {
            assocCalledMoreThanThrice.setValue(true);
            throw new IllegalStateException("Unexpected move by recorder... Time to chicken out!");
        };
        MutableObject<Recorder.PollReq> pollReq = new MutableObject<>();
        
        MutableLong associate2PolledAt = new MutableLong();

        poll[0] = (req) -> {
            associate2PolledAt.setValue(currentTimeMillis());
            return tellRecorderWeHaveNoWork(pollReq).apply(req);
        };

        long startTime = currentTimeMillis();
        runner.start();
        
        Thread.sleep(8000);
        
        server.start();

        assocAction[0].get(6, TimeUnit.SECONDS);
        server.stop();
        Thread.sleep(8000);
        server.start();
        
        assocAction[1].get(6, TimeUnit.SECONDS);
        
        Thread.sleep(8000);
        
        assocAction[2].get(6, TimeUnit.SECONDS);
        
        assertRecorderInfoAllGood_AndGetTick(recInfo.getValue(), is(0l), rc(true));
        
        pollAction[0].get(2, TimeUnit.SECONDS);
        
        Recorder.PollReq pollRequest = pollReq.getValue();
        assertThat(pollRequest, is(notNullValue()));
        assertRecorderInfoAllGood_AndGetTick(pollRequest.getRecorderInfo(), is(0l), rc(true));
        Recorder.WorkResponse workLastIssued = pollReq.getValue().getWorkLastIssued();
        assertReportedBootstrapWorkCompletion(workLastIssued);

        assertThat(assocCalledMoreThanThrice.getValue(), is(false));
    }

    private void assertReportedBootstrapWorkCompletion(Recorder.WorkResponse workLastIssued) {
        assertThat(workLastIssued.getWorkId(), is(0l));
        assertThat(workLastIssued.getWorkState(), is(Recorder.WorkResponse.WorkState.complete));
        assertThat(workLastIssued.getWorkResult(), is(Recorder.WorkResponse.WorkResult.success));
        assertThat(workLastIssued.getElapsedTime(), is(0));
    }

    private Function<byte[], byte[]> tellRecorderWeHaveNoWork(MutableObject<Recorder.PollReq> pollReq) {
        return (req) -> {
            try {
                Recorder.PollReq.Builder pollReqBuilder = Recorder.PollReq.newBuilder();
                pollReq.setValue(pollReqBuilder.mergeFrom(req).build());
                DateTime now = DateTime.now();
                String nowString = ISODateTimeFormat.dateTime().print(now);
                Recorder.PollRes.Builder builder = Recorder.PollRes.newBuilder()
                        .setLocalTime(nowString)
                        .setControllerId(2)
                        .setControllerVersion(1);
                builder.getAssignmentBuilder()
                        .setDescription("no work for ya!")
                        .setWorkId(10)
                        .setDelay(0)
                        .setDuration(0)
                        .setIssueTime(nowString);
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

    private Function<byte[], byte[]> pointToAssociate(MutableObject<Recorder.RecorderInfo> recInfo, final int associatePort) {
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

    public static long assertRecorderInfoAllGood_AndGetTick(Recorder.RecorderInfo recorderInfo, final Matcher<Long> recorderTickMatcher, final Recorder.RecorderCapabilities rc) {
        assertThat(recorderInfo.getIp(), is("10.20.30.40"));
        assertThat(recorderInfo.getHostname(), is("foo-host"));
        assertThat(recorderInfo.getAppId(), is("bar-app"));
        assertThat(recorderInfo.getInstanceGrp(), is("baz-grp"));
        assertThat(recorderInfo.getCluster(), is("quux-cluster"));
        assertThat(recorderInfo.getInstanceId(), is("corge-iid"));
        assertThat(recorderInfo.getProcName(), is("grault-proc"));
        assertThat(recorderInfo.getVmId(), is(getVmInfo() + "garply-vmid"));
        assertThat(recorderInfo.getZone(), is("waldo-zone"));
        assertThat(recorderInfo.getInstanceType(), is("c0.small"));
        DateTime dateTime = ISODateTimeFormat.dateTimeParser().parseDateTime(recorderInfo.getLocalTime());
        DateTime now = DateTime.now();
        assertThat(dateTime, allOf(greaterThan(now.minusMinutes(1)), lessThan(now.plusMinutes(1))));
        assertThat(recorderInfo.getRecorderVersion(), is(1));
        assertThat(recorderInfo.getRecorderUptime(), allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(60)));
        Recorder.RecorderCapabilities capabilities = recorderInfo.getCapabilities();
        assertEquals(capabilities, rc);
        long recorderTick = recorderInfo.getRecorderTick();
        assertThat(recorderTick, recorderTickMatcher);
        return recorderTick;
    }

    private static String getVmInfo() {
        String vmInfoHack = wrap(System.getProperty("java.vm.info") + ", sharing");
        return p("java.vm.name") + p("java.vm.specification.version") + p("java.vm.version") + vmInfoHack + p("java.vm.vendor");
    }

    private static String p(String key) {
        return wrap(System.getProperty(key));
    }

    private static String wrap(String value) {
        return value + "; ";
    }

    public static Recorder.RecorderCapabilities rc(boolean cpuSamp) {
        Recorder.RecorderCapabilities.Builder b = Recorder.RecorderCapabilities.newBuilder();
        b.setCanCpuSample(cpuSamp);
        return b.build();
    }
}
