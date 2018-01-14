package fk.prof.bciagent;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class Util {

    public static final String defaultTestServerResponse = "Hi there!!!";

    public static String getInstumentedCallerName() {
        StackTraceElement[] straces = Thread.currentThread().getStackTrace();
        return straces.length > 3 ? (straces[3].getClassName() + "#" + straces[3].getMethodName()) : "";
    }

    public static HttpServer startHttpNioServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(28080), 0);
        server.createContext("/test", req -> {
            String response = defaultTestServerResponse;
            req.sendResponseHeaders(200, response.length());
            OutputStream os = req.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });
        server.setExecutor(null); // creates a default executor
        server.start();
        return server;
    }
}
