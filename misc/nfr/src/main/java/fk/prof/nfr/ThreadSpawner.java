package fk.prof.nfr;

import java.util.Random;

/**
 * Created by gaurav.ashok on 16/09/17.
 */
public class ThreadSpawner {

    int n, maxSleepDuration;

    boolean isDebug;

    public ThreadSpawner(int n, int maxSleepDuration, boolean isDebug) {
        this.n = n;
        this.isDebug = isDebug;
        this.maxSleepDuration = maxSleepDuration;
    }

    public void doWork() {

        Thread[] threads = new Thread[n];

        while (true) {
            for (int i = 0; i < n; ++i) {

                if (threads[i] != null) {
                    while (true) {
                        boolean joined = false;
                        try {
                            threads[i].join();
                            joined = true;
                        } catch (Exception e) {
                        }

                        if (joined) {
                            break;
                        }
                    }
                }

                final int th_id = i;
                threads[i] = new Thread(() -> {
                    try {
                        if (isDebug) {
                            System.out.println("Threadspawner: thd " + th_id + " started");
                        }
                        Thread.sleep(100 + (new Random().nextInt() % maxSleepDuration)); // atleast 100 ms.
                        if (isDebug) {
                            System.out.println("Threadspawner: thd " + th_id + " will now end");
                        }
                    } catch (Exception e) {
                    }
                });

                threads[i].start();
            }

            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }
}
