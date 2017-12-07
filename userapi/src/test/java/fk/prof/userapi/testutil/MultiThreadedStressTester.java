package fk.prof.userapi.testutil;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MultiThreadedStressTester {
  private final int threadCount;
  private ExecutorService executor;

  public MultiThreadedStressTester(int threadCount) {
    this.threadCount = threadCount;
    executor = Executors.newFixedThreadPool(threadCount);

  }

  public void stress(final Runnable action) throws InterruptedException {
    spawnThreads(action).await();
  }
  private CountDownLatch spawnThreads(final Runnable action) {
    final CountDownLatch finished = new CountDownLatch(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executor.execute(() -> {
        try {
          repeat(action, 1);
        }
        finally {
          finished.countDown();
        }
      });
    }
    return finished;
  }
  private void repeat(Runnable action, int times) {
    for (int i = 0; i < times; i++) {
      action.run();
    }
  }

  public void shutdown() {
    executor.shutdown();
  }
}