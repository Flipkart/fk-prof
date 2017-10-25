package fk.prof.bciagent;

public class BciTest {
  @Profile({ProfileType.OFF_CPU})
  public void profiledRun() throws InterruptedException {
    long a = 2;
    for(int i = 0; i<50; i++) {
      a = a * 2;
    }
    Thread.sleep(2000);
    System.out.println("a=" + a);
  }

  @Profile({ProfileType.OFF_CPU})
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

  public static void main(String[] args)  throws InterruptedException {
    BciTest t = new BciTest();
    t.profiledRun();
    t.run();
    t.exceptionRun();
  }
}
