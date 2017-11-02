package fk.prof.bciagenttest;

import fk.prof.InstrumentationStub;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.lang.reflect.Field;

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
    InstrumentationStub.entryTracepoint();
    BufferedReader br = null;
    FileReader fr = null;
    try {
      fr = new FileReader("/tmp/hello");
      br = new BufferedReader(fr);
      Field[] farr = FileReader.class.getDeclaredFields();
      for(Field f: farr) {
        if(f.getName().equals("lock")) {
          System.out.println("Found fileinputstream object");
          FileInputStream b = (FileInputStream) f.get(fr);
          System.out.println(b);
        }
      }
      String sCurrentLine;
      while ((sCurrentLine = br.readLine()) != null) {
        System.out.println(sCurrentLine);
      }
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

  public static void main(String[] args)  throws InterruptedException {
    BciTest t = new BciTest();
//    t.profiledRun();
//    t.run();
    t.fileRead();
//    t.exceptionRun();
  }
}
