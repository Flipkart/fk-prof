package fk.prof.recorder.io;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class IOWorkload {

    public static int bytesToWrite = 1024;

    public static class BasicFileStdOutWrite {
        public static void main(String[] args) throws Exception {
            Thread thd = new Thread(new WriterThread());
            thd.start();

            while(true) {
                System.out.print(new char[bytesToWrite / 2]);
                System.out.flush();
                sleep();
            }
        }
    }

    static class WriterThread implements Runnable {

        @Override
        public void run() {
            Path tmpFile, tempDir;
            try {
                tempDir = Files.createTempDirectory("fk-prof-bciagent-e2etest");

                Runtime.getRuntime().addShutdownHook(new Thread(() ->
                {
                    try {
                        Files.delete(tempDir);
                    } catch (Exception e) {
                        // ignore
                    }
                }));

                tmpFile = Files.createTempFile(tempDir, "temp1", ".tmp");

                try (FileWriter fw = new FileWriter(tmpFile.toFile(), false)) {
                    while(true) {
                        fw.write(new char[bytesToWrite]);
                        fw.flush();
                        sleep();
                    }
                } catch (InterruptedException ie) {
                    return;
                } finally {
                    try {
                        Files.delete(tmpFile);
                    } catch (Exception e2) {
                        // ignore
                    }
                }
            } catch (Exception e1) {
                throw new RuntimeException(e1);
            }
        }
    }

    static void sleep() throws InterruptedException {
        Thread.sleep(1000);
    }
}
