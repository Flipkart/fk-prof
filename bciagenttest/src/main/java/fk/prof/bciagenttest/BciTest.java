package fk.prof.bciagenttest;

import java.io.*;
import java.net.URL;

public class BciTest {
  @ProfileOffCpu
  public void profiledRun() throws InterruptedException {
    long a = 2;
    for(int i = 0; i<50; i++) {
      a = a * 2;
    }
    Thread.sleep(2000);
    System.out.println("a=" + a);
  }

  @ProfileOffCpu
  public long exceptionRun() throws InterruptedException {
    long a = 2;
    for(int i = 0; i<50; i++) {
      a = a * 2;
    }
    if (a > 100) {
      throw new RuntimeException("Runtime exception");
    }
    Thread.sleep(2000);
    return a;
  }

  public void run() throws InterruptedException {
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

  public void httpRequest() {
    try {
      URL url = new URL("http://www.google.com/");
      InputStream is = url.openStream();
      InputStreamReader isr = new InputStreamReader(is);
      BufferedReader in = new BufferedReader(isr);

      String inputLine;
      while ((inputLine = in.readLine()) != null)
        System.out.println(inputLine);
      in.close();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static void main(String[] args)  throws Exception, InterruptedException {
    BciTest t = new BciTest();
//    t.profiledRun();
//    t.run();
    t.fileRead();
//    t.exceptionRun();
    t.httpRequest();
  }
}
