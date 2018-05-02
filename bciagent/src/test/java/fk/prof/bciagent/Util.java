package fk.prof.bciagent;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Util {

    public static final String defaultTestServerResponse = "server msg: !!! Hi there !!!";
    public static final String defaultTestClientResponse = "client msg: -- Hello world --";

    public static String getCallerName(int depth) {
        StackTraceElement[] straces = Thread.currentThread().getStackTrace();
        return straces.length > depth ? (straces[depth].getClassName() + "#" + straces[3].getMethodName()) : "";
    }

    public static String getCallerName() {
        return getCallerName(4);
    }

    public static Thread startServer(int millis) {
        Thread thd = new Thread(new Server(millis, false, false));
        thd.start();
        return thd;
    }

    public static Thread startServerThenStop(int millis, boolean beforeRead) {
        Thread thd = new Thread(new Server(millis, true, beforeRead));
        thd.start();
        return thd;
    }

    public static Thread startNioServer(int millis) {
        Thread thd = new Thread(new NioServer(millis, false, false));
        thd.start();
        return thd;
    }

    public static Thread startNioServerThenStop(int millis, boolean beforeRead) {
        Thread thd = new Thread(new NioServer(millis, true, beforeRead));
        thd.start();
        return thd;
    }

    public static Thread startServer() {
        return startServer(0);
    }

    public static Thread startNioServer() {
        return startNioServer(0);
    }

    public static class Server implements Runnable {

        private int responseDelay;
        private boolean stopAfterDelay;
        private boolean beforeRead;

        public Server(int responseDelay, boolean stopAfterDelay, boolean beforeRead) {
            this.responseDelay = responseDelay;
            this.stopAfterDelay = stopAfterDelay;
            this.beforeRead = beforeRead;
        }

        public void run() {
            try {
                try (
                        java.net.ServerSocket serverSocket = new java.net.ServerSocket(19090);
                        Socket clientSocket = serverSocket.accept();
                        PrintWriter out =
                                new PrintWriter(clientSocket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(clientSocket.getInputStream()));
                ) {
                    if(beforeRead) {
                        Thread.sleep(responseDelay);
                        if(stopAfterDelay) {
                            return;
                        }
                    }

                    // consume response
                    in.readLine();

                    if(!beforeRead) {
                        Thread.sleep(responseDelay);
                        if(stopAfterDelay) {
                            return;
                        }
                    }

                    out.println(defaultTestServerResponse);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static class NioServer implements Runnable {

        private int responseDelay;
        private boolean stopAfterDelay;
        private boolean beforeRead;

        private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

        public NioServer(int responseDelay, boolean stopAfterDelay, boolean beforeRead) {
            this.responseDelay = responseDelay;
            this.stopAfterDelay = stopAfterDelay;
            this.beforeRead = beforeRead;
        }

        private ServerSocketChannel initSelector() throws IOException {

            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);

            // Bind the server socket to the specified address and port
            InetSocketAddress isa = new InetSocketAddress("localhost", 19090);
            serverChannel.socket().bind(isa);

            return serverChannel;
        }

        @Override
        public void run() {
            try {
                try (
                        ServerSocketChannel serverChannel = initSelector();
                        SocketChannel clientChannel = serverChannel.accept();
                ) {
                    if (beforeRead) {
                        Thread.sleep(responseDelay);
                        if (stopAfterDelay) {
                            return;
                        }
                    }

                    clientChannel.read(readBuffer);

                    if (!beforeRead) {
                        Thread.sleep(responseDelay);
                        if (stopAfterDelay) {
                            return;
                        }
                    }

                    clientChannel.write(ByteBuffer.wrap(defaultTestServerResponse.getBytes("utf-8")));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
