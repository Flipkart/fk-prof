package fk.prof.bciagent.test;

import com.sun.net.httpserver.HttpServer;
import fk.prof.bciagent.TestBciAgent;
import fk.prof.bciagent.Util;
import org.junit.*;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

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
        TestBciAgent.tracerEvents.clear();
    }

    /**
     * Files
     */

//    @Test
    public void testOpenFile() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp1", ".tmp");

        try (FileWriter fw = new FileWriter(tmpFile.toFile(), false)) {
        }

        printEvents();
    }

//    @Test
    public void testReadWriteFile() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp2", ".tmp");

        try (FileWriter fw = new FileWriter(tmpFile.toFile(), false)) {
            TestBciAgent.tracerEvents.clear();

            for(int i = 0; i < 10; ++i) {
                fw.write(new char[i * 1024]);
                fw.flush();
            }

            printEvents();
        }

        try(FileReader fr = new FileReader(tmpFile.toFile())) {
            TestBciAgent.tracerEvents.clear();

            char[] buf = new char[10 * 1024];
            for(int i = 0; i < 10; ++i) {
                fr.read(buf, 0, i * 1024);
            }

            printEvents();
        }
    }

//    @Test
    public void testExceptionFileOpen() throws Exception {
        TestBciAgent.tracerEvents.clear();
        try(FileReader fr = new FileReader(tempDir + "/not_exisiting_file")) {
        } catch (Exception e) {
            // expect a exception here
        }
        printEvents();

        TestBciAgent.tracerEvents.clear();
        Path newTmpDir = Files.createTempDirectory(tempDir, "tempdir");
        try(FileWriter fw = new FileWriter(newTmpDir.toFile(), true)) {
        } catch (Exception e) {
            // expect a exception here
        }
        printEvents();
    }

//    @Test
    public void testExceptionFileWriteOnClosed() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp2", ".tmp");

        Exception exception = null;

        FileOutputStream fout = new FileOutputStream(tmpFile.toFile());
        fout.write(new byte[10]);

        fout.close();

        clearEvents();
        try {
            fout.write(new byte[20]);
        } catch (Exception e) {
            exception  = e;
        }

        // check for -1 fd
        Assert.assertNotNull(exception);

        printEvents();
    }

//    @Test
    public void testExceptionFileReadOnClosed() throws Exception {
        Path tmpFile = Files.createTempFile(tempDir, "temp2", ".tmp");

        Exception exception = null;

        FileInputStream fin = new FileInputStream(tmpFile.toFile());
        fin.close();

        clearEvents();
        try {
            byte[] buf = new byte[10];
            fin.read(buf);
        } catch (Exception e) {
            exception  = e;
        }

        // check for -1 fd
        Assert.assertNotNull(exception);

        printEvents();
    }

    /**
     *  Sockets
     */
    @Test
    public void testSocketReadWrite() throws Exception {
        clearEvents();

        HttpServer server = Util.startHttpNioServer();

        String response = "";

        try {
            URL url = new URL("http://localhost:28080/test");
            InputStream is = url.openStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader in = new BufferedReader(isr);

            String inputLine;

            while ((inputLine = in.readLine()) != null)
                response += inputLine;
            in.close();
        } catch (Exception ex) {
            // no exception
        } finally {
            server.start();
        }

        printEvents();
        Assert.assertEquals(response, Util.defaultTestServerResponse);
    }

    private void clearEvents() {
        TestBciAgent.tracerEvents.clear();
    }

    public static void printEvents() {
        int eventsCount = TestBciAgent.tracerEvents.size();
        System.out.println("events count: " + eventsCount);

        for(int i = 0; i < eventsCount; ++i) {
            System.out.println(TestBciAgent.tracerEvents.get(i));
        }
    }
}
