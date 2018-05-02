package fk.prof.bciagent.test;

import fk.prof.bciagent.Event;
import fk.prof.bciagent.TestBciAgent;
import fk.prof.bciagent.Util;
import org.junit.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BciIT {

    static Path tempDir;

    @BeforeClass
    public static void setup() throws Exception {
        tempDir = Files.createTempDirectory("fk-prof-bciagent-test");
    }

    @AfterClass
    public static void destroy() throws Exception {
        for(File f : tempDir.toFile().listFiles()) {
            f.delete();
        }
        Files.delete(tempDir);
    }

    @Before
    public void beforeTest() {
        clearEvents();
    }

    /**
     * Files
     */

    @Test
    public void testOpenFile() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp1", ".tmp");

        try (FileWriter fw = new FileWriter(tmpFile.toFile(), false)) {
        }

        List<Event> openEvt = findOpenEvts();
        Assert.assertEquals(1, openEvt.size());
        Assert.assertNotEquals(-1, openEvt.get(0).fd);
        Assert.assertEquals(
                new Event("", 0, 0, openEvt.get(0).fd, Event.File.open(tmpFile.toString())),
                openEvt.get(0));
    }

    @Test
    public void testReadWriteFile() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp2", ".tmp");

        long totalBytesWritten = 0;
        try (FileWriter fw = new FileWriter(tmpFile.toFile(), false)) {
            for(int i = 0; i < 10; ++i) {
                fw.write(new char[i * 1024]);
                fw.flush();
                totalBytesWritten += i * 1024;
            }
        }

        try(FileReader fr = new FileReader(tmpFile.toFile())) {
            char[] buf = new char[10 * 1024];
            for(int i = 0; i < 10; ++i) {
                fr.read(buf, 0, i * 1024);
            }
        }

        List<Event> openEvt = filter(e -> isFileEvt(e, Event.File.EvtType.open)).collect(Collectors.toList());
        Assert.assertEquals(2, openEvt.size());

        Event openEvtForWrite, openEvtForRead;
        if(openEvt.get(0).caller.contains("FileOutputStream")) {
            openEvtForWrite = openEvt.get(0);
            openEvtForRead = openEvt.get(1);
        } else {
            openEvtForWrite = openEvt.get(1);
            openEvtForRead = openEvt.get(0);
        }

        long totalWrite = totalCountForFile(openEvtForWrite.fd, false);
        long totalRead = totalCountForFile(openEvtForRead.fd, true);

        Assert.assertEquals(totalBytesWritten, totalWrite);
        Assert.assertEquals(totalBytesWritten, totalRead);
    }

    @Test
    public void testExceptionNonExistentFileOpen() throws Exception {
        Path nonExisitentFile = tempDir.resolve("not_exisiting_file");

        try (FileReader fr = new FileReader(nonExisitentFile.toFile())) {
        } catch (IOException e) {
            List<Event> openEvt = findOpenEvts();
            Assert.assertEquals(1, openEvt.size());
            Assert.assertEquals(
                    new Event("", 0, 0, -1, Event.File.open(nonExisitentFile.toString())),
                    openEvt.get(0));
            return;
        }

        Assert.fail("exception was expected");
    }

    @Test
    public void testExceptionOpenDirectoryForWrite() throws Exception {
        Path newTmpDir = Files.createTempDirectory(tempDir, "tempdir");

        try(FileWriter fw = new FileWriter(newTmpDir.toFile(), true)) {
        } catch (IOException e) {
            List<Event> openEvt = findOpenEvts();
            Assert.assertEquals(1, openEvt.size());
            Assert.assertEquals(
                    new Event("", 0, 0, -1, Event.File.open(newTmpDir.toString())),
                    openEvt.get(0));

            return;
        }

        Assert.fail("exception was expected");
    }

    @Test
    public void testExceptionFileWriteOnClosed() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp2", ".tmp");
        FileOutputStream fout = new FileOutputStream(tmpFile.toFile());
        fout.write(new byte[10]);
        fout.close();

        try {
            fout.write(new byte[191]);
        } catch (IOException e) {
            List<Event> openEvt = findOpenEvts();
            Assert.assertEquals(1, openEvt.size());

            List<Event> fileOps = filter(evt -> isFileEvt(evt, Event.File.EvtType.write)).collect(Collectors.toList());
            fileOps.sort(this::compareTs);

            Assert.assertEquals(new Event("", 0, 0, -1, Event.File.write(191)), fileOps.get(0));
            return;
        }

        Assert.fail("exception was expected");
    }

    @Test
    public void testExceptionFileReadOnClosed() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp2", ".tmp");
        FileInputStream fin = new FileInputStream(tmpFile.toFile());
        fin.close();

        try {
            byte[] buf = new byte[10];
            fin.read(buf);
        } catch (IOException e) {
            List<Event> openEvt = findOpenEvts();
            Assert.assertEquals(1, openEvt.size());

            List<Event> fileOps = filter(evt -> isFileEvt(evt, Event.File.EvtType.read)).collect(Collectors.toList());
            fileOps.sort(this::compareTs);

            Assert.assertEquals(new Event("", 0, 0, -1, Event.File.read(0)), fileOps.get(0));
            return;
        }

        Assert.fail("exception was expected");
    }

    /**
     *  Sockets
     */
    @Test(timeout = 10_000)
    public void testSocketReadWrite() throws Exception {
        Util.startServer();

        Thread.sleep(500);
        clearEvents();
        try (
                Socket server = new Socket("localhost", 19090);
                PrintWriter out = new PrintWriter(server.getOutputStream(), true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(server.getInputStream()));
        ) {
            out.println(Util.defaultTestClientResponse);
            while (in.readLine() != null);
        } catch (Exception e) {
            Assert.fail("no exception was expected");
        }

        Event acceptEvt = findAcceptEvt();
        Event connectEvt = findConnectEvt();

        List<Event> acceptFdOps = socketOps(acceptEvt);
        List<Event> connectFdOps = socketOps(connectEvt);

        Assert.assertTrue(acceptFdOps.size() > 0);
        Assert.assertTrue(connectFdOps.size() > 0);

        // write by server == reads by client
        Assert.assertEquals(totalCountForSocket(acceptEvt, true), totalCountForSocket(connectEvt, false));
        // reads by server == writes by client
        Assert.assertEquals(totalCountForSocket(acceptEvt, false), totalCountForSocket(connectEvt, true));
    }

    @Test(timeout = 10_000)
    public void testSocketReadTimeout() throws Exception {
        // 2 sec delay in response
        Thread serverThd = Util.startServer(2000);

        Thread.sleep(500);
        clearEvents();
        try (
                Socket server = new Socket("localhost", 19090);
                PrintWriter out = new PrintWriter(server.getOutputStream(), true);
        ) {
            server.setSoTimeout(1000);
            InputStream in = server.getInputStream();
            out.println(Util.defaultTestClientResponse);
            while (in.read() != -1);
        } catch (SocketTimeoutException e) {
            Event acceptEvt = findAcceptEvt();
            Event connectEvt = findConnectEvt();

            List<Event> acceptFdOps = socketOps(acceptEvt);
            List<Event> connectFdOps = socketOps(connectEvt);

            Assert.assertTrue(acceptFdOps.size() > 0);
            Assert.assertTrue(connectFdOps.size() > 0);

            // write by server == reads by client
            Assert.assertEquals(totalCountForSocket(acceptEvt, true), totalCountForSocket(connectEvt, false));
            // reads by server == writes by client
            Assert.assertEquals(totalCountForSocket(acceptEvt, false), totalCountForSocket(connectEvt, true));

            // read 0 bytes in the last read
            Assert.assertEquals(0, asSocketEvt(connectFdOps.get(0)).count);
            // timedout
            Assert.assertTrue(asSocketEvt(connectFdOps.get(0)).timedout);

            return;
        } finally {
            serverThd.join(5000);
        }

        Assert.fail("exception was expected");
    }

    @Test(timeout = 10_000)
    public void testExceptionReadOnClosedSocket() throws Exception {
        Util.startServerThenStop(2000, true);

        Thread.sleep(500);
        clearEvents();

        try (
                Socket server = new Socket("localhost", 19090);
                PrintWriter out = new PrintWriter(server.getOutputStream(), true);
        ) {
            Thread.sleep(3000);
            InputStream in = server.getInputStream();

            // expect eof
            Assert.assertEquals(-1, in.read());
        } catch (Exception e) {
            Assert.fail("exception was not expected");
        }

        Event acceptEvt = findAcceptEvt();
        Event connectEvt = findConnectEvt();

        List<Event> acceptFdOps = socketOps(acceptEvt);
        List<Event> connectFdOps = socketOps(connectEvt);

        Assert.assertEquals(0, acceptFdOps.size());
        Assert.assertTrue(connectFdOps.size() > 0);

        // eof
        Assert.assertEquals(-1, asSocketEvt(connectFdOps.get(0)).count);
        // total read = 0
        Assert.assertEquals(0, totalCountForSocket(connectEvt, true));
    }


    @Test(timeout = 10_000)
    public void testExceptionWriteOnClosedSocket() throws Exception {
        Thread serverThd = Util.startServerThenStop(1000, true);

        int writeAttempts;

        Thread.sleep(500);
        clearEvents();
        try (
                Socket server = new Socket("localhost", 19090);
        ) {
            Thread.sleep(2000);
            serverThd.join(1000);

            for(writeAttempts = 0; writeAttempts < 10; ++writeAttempts) {
                server.getOutputStream().write(Util.defaultTestClientResponse.getBytes("utf-8"));
            }
        } catch (IOException e) {
            Event acceptEvt = findAcceptEvt();
            Event connectEvt = findConnectEvt();

            List<Event> acceptFdOps = socketOps(acceptEvt);
            List<Event> connectFdOps = socketOps(connectEvt);

            // no ops from server side
            Assert.assertEquals(0, acceptFdOps.size());
            // there can ops from client side, there shouldn't be any evts for -1 fd.
            Assert.assertEquals(0, recordedEvts().stream().filter(evt -> evt.fd == -1).count());
            return;
        }

        Assert.fail("exception was expected");
    }

    @Test(timeout = 10_000)
    public void testSocketChannelReadWrite() throws Exception {
        Util.startNioServer();

        Thread.sleep(500);
        clearEvents();
        try (
                SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", 19090))
        ) {
            ByteBuffer buf = ByteBuffer.allocate(1024);
            channel.write(ByteBuffer.wrap(Util.defaultTestClientResponse.getBytes("utf-8")));
            while (channel.read(buf) > 0);
        } catch (Exception e) {
            Assert.fail("no exception was expected");
        }

        Event acceptEvt = findAcceptEvt();
        Event connectEvt = findConnectEvt();

        List<Event> acceptFdOps = socketOps(acceptEvt);
        List<Event> connectFdOps = socketOps(connectEvt);

        Assert.assertTrue(acceptFdOps.size() > 0);
        Assert.assertTrue(connectFdOps.size() > 0);

        // write by server == reads by client
        Assert.assertEquals(totalCountForSocket(acceptEvt, true), totalCountForSocket(connectEvt, false));
        // reads by server == writes by client
        Assert.assertEquals(totalCountForSocket(acceptEvt, false), totalCountForSocket(connectEvt, true));
    }

    @Test(timeout = 10_000)
    public void testSocketChannelReadOnClosedSocket() throws Exception {
        Util.startNioServerThenStop(2000, true);

        Thread.sleep(500);

        clearEvents();
        try (
                SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", 19090))
        ) {
            ByteBuffer buf = ByteBuffer.allocate(1024);
            Thread.sleep(3000);
            // eof
            Assert.assertEquals(-1, channel.read(buf));
        } catch (Exception e) {
            Assert.fail("no exception was expected");
        }

        Event acceptEvt = findAcceptEvt();
        Event connectEvt = findConnectEvt();

        List<Event> acceptFdOps = socketOps(acceptEvt);
        List<Event> connectFdOps = socketOps(connectEvt);

        Assert.assertEquals(0, acceptFdOps.size());
        Assert.assertTrue(connectFdOps.size() > 0);

        // eof
        Assert.assertEquals(-1, asSocketEvt(connectFdOps.get(0)).count);
        // total read = 0
        Assert.assertEquals(0, totalCountForSocket(connectEvt, true));
    }

    @Test(timeout = 10_000)
    public void testSocketChannelWriteOnClosedSocket() throws Exception {
        Util.startNioServerThenStop(1000, true);

        Thread.sleep(500);
        clearEvents();
        try (
                SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", 19090))
        ) {
            Thread.sleep(3000);
            while(channel.write(ByteBuffer.wrap(Util.defaultTestClientResponse.getBytes("utf-8"))) > 0);
        } catch (IOException e) {
            Event acceptEvt = findAcceptEvt();
            Event connectEvt = findConnectEvt();

            List<Event> acceptFdOps = socketOps(acceptEvt);
            List<Event> connectFdOps = socketOps(connectEvt);

            Assert.assertEquals(0, acceptFdOps.size());
            Assert.assertTrue(connectFdOps.size() > 0);

            Assert.assertEquals(0, asSocketEvt(connectFdOps.get(0)).count);

            return;
        }

        Assert.fail("Exception was expected");
    }

    private Event.Socket asSocketEvt(Event e) {
        return (Event.Socket) e.data;
    }

    private Event.File asFileEvt(Event e) {
        return (Event.File) e.data;
    }

    private long totalCountForSocket(Event beginEvt, boolean read) {
        return socketOps(beginEvt).stream()
                .filter(e -> isSocketEvt(e, read ? Event.Socket.EvtType.read : Event.Socket.EvtType.write))
                .map(this::asSocketEvt).filter(e -> e.count >= 0)
                .mapToLong(e -> e.count).sum();
    }

    private long totalCountForFile(int fd, boolean read) {
        return fileOps(fd, read).stream()
                .filter(e -> isFileEvt(e, read ? Event.File.EvtType.read : Event.File.EvtType.write))
                .map(this::asFileEvt).filter(e -> e.count >= 0)
                .mapToLong(e -> e.count).sum();
    }

    private Event findConnectEvt() {
        return filter(e -> isSocketEvt(e, Event.Socket.EvtType.connect)).findFirst().get();
    }

    private Event findAcceptEvt() {
        return filter(e -> isSocketEvt(e, Event.Socket.EvtType.accept)).findFirst().get();
    }

    private List<Event> findOpenEvts() {
        return filter(e -> e.data instanceof Event.File && ((Event.File) e.data).type == Event.File.EvtType.open)
                .collect(Collectors.toList());
    }

    private int compareTs(Event e1, Event e2) {
        if(ts(e1) > ts(e2)) return -1;
        else if(ts(e1) < ts(e2)) return 1;
        else return 0;
    }

    private boolean isSocketEvt(Event evt, Event.Socket.EvtType type) {
        return evt.data instanceof Event.Socket && ((Event.Socket) evt.data).type == type;
    }

    private boolean isFileEvt(Event evt, Event.File.EvtType type) {
        return evt.data instanceof Event.File && ((Event.File) evt.data).type == type;
    }

    private List<Event> socketOps(Event beginEvt) {
        return filter(e -> e.fd == beginEvt.fd &&
                    e.data instanceof Event.Socket &&
                    !isSocketEvt(e, Event.Socket.EvtType.connect) &&
                    !isSocketEvt(e, Event.Socket.EvtType.accept))
                .sorted(this::compareTs)
                .collect(Collectors.toList());
    }

    private List<Event> fileOps(int fd, boolean read) {
        return filter(e -> e.fd == fd &&
                isFileEvt(e, read ? Event.File.EvtType.read : Event.File.EvtType.write))
                .sorted(this::compareTs)
                .collect(Collectors.toList());
    }

    private Stream<Event> filter(Predicate<Event> testFunc) {
        return recordedEvts().stream().filter(testFunc);
    }

    private void clearEvents() {
        recordedEvts().clear();
    }

    private static void printEvents() {
        int eventsCount = TestBciAgent.tracerEvents.size();
        System.out.println("events count: " + eventsCount);

        for(int i = 0; i < eventsCount; ++i) {
            System.out.println(TestBciAgent.tracerEvents.get(i));
        }
    }

    private long ts(Event e) {
        return e.evt_created - e.elapsed_ns;
    }

    private List<Event> recordedEvts() {
        return TestBciAgent.tracerEvents;
    }
}
