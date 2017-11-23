package fk.prof.bciagenttest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

public class BciTest {

  public void runAndSleep() throws InterruptedException {
    long a = 1;
    for(int i = 0; i<50; i++) {
      a = a * 2;
    }
    Thread.sleep(2000);
    System.out.println("a=" + a);
  }

  public void fileRead() {
    BufferedReader br = null;
    FileReader fr = null;
    try {
      fr = new FileReader("/tmp/hello");
      br = new BufferedReader(fr);
      char[] buff = new char[16384];
      long readChars, totalChars = 0;
      while ((readChars = br.read(buff)) != -1) {
//        System.out.println(sCurrentLine);
        totalChars += readChars;
        System.out.println("total chars read till now: " + totalChars);
      }
      System.out.println("Total chars: "  + totalChars);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        if (br != null) { br.close(); }
        if (fr != null) { fr.close(); }
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
  }

  public void externalHttpRequest() throws Exception {
    try {
      URL url = new URL("http://www.google.com/");
      InputStream is = url.openStream();
      InputStreamReader isr = new InputStreamReader(is);
      BufferedReader in = new BufferedReader(isr);

      System.out.println("\nExternal HTTP Response");
      String inputLine;
      while ((inputLine = in.readLine()) != null)
        System.out.println(inputLine);
      in.close();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public HttpServer startHttpNioServer() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(28080), 0);
    server.createContext("/test", new TestHandler());
    server.setExecutor(null); // creates a default executor
    server.start();
    return server;
  }

  static class TestHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
      String response = "This is the response";
      t.sendResponseHeaders(200, response.length());
      OutputStream os = t.getResponseBody();
      os.write(response.getBytes());
      os.close();
    }
  }

  public void internalHttpRequest() throws Exception {
    try {
      URL url = new URL("http://localhost:28080/test");
      InputStream is = url.openStream();
      InputStreamReader isr = new InputStreamReader(is);
      BufferedReader in = new BufferedReader(isr);

      System.out.println("\nInternal HTTP Response");
      String inputLine;
      while ((inputLine = in.readLine()) != null)
        System.out.println(inputLine);
      in.close();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static void serverSocketClient() throws Exception {
    try (
        Socket kkSocket = new Socket("localhost", 19090);
        PrintWriter out = new PrintWriter(kkSocket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(
            new InputStreamReader(kkSocket.getInputStream()));
    ) {
      String fromServer;
      boolean respWritten = false;
      while ((fromServer = in.readLine()) != null) {
        System.out.println("Server says: " + fromServer);
        if (fromServer.equals("Bye"))
          break;

        if(respWritten) {
          out.println("Bye");
        } else {
          out.println("Bla bla says client");
          respWritten = true;
        }
      }
    }
  }

  public static void main(String[] args)  throws Exception {
    BciTest t = new BciTest();
//    t.runAndSleep();
//    t.fileRead();
//    t.externalHttpRequest();
//    HttpServer server = t.startHttpNioServer();
//    t.internalHttpRequest();
//    server.stop(0);
    ServerSocketThread srvrthd = new ServerSocketThread();
    srvrthd.start();
    serverSocketClient();
    srvrthd.join();
  }
}
