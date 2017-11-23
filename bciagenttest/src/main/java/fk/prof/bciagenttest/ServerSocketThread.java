package fk.prof.bciagenttest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerSocketThread extends Thread {
  public void run() {
    try {
      try (
          ServerSocket serverSocket = new ServerSocket(19090);
          Socket clientSocket = serverSocket.accept();
          PrintWriter out =
              new PrintWriter(clientSocket.getOutputStream(), true);
          BufferedReader in = new BufferedReader(
              new InputStreamReader(clientSocket.getInputStream()));
      ) {
        String inputLine;
        out.println("Hello");
        while ((inputLine = in.readLine()) != null) {
          if (inputLine.equalsIgnoreCase("Bye")) {
            out.println("Bye");
            break;
          }
          out.println("Client says: " + inputLine);
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }
}
