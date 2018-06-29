import fk.prof.AsyncTaskCtx;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class RunnableDemo implements Runnable {
    private String threadName;

    RunnableDemo(String name) {
        threadName = name;
        System.out.println("Creating " + threadName);
    }

    public void run() {
        System.out.println("Running " + threadName);
        try {
            // Let the thread sleep for a while.
            Thread.sleep(500);
        } catch (InterruptedException e) {
            System.out.println("Thread " + threadName + " interrupted.");
        }
        System.out.println("Thread " + threadName + " exiting.");
    }
}

class TempRunnable implements Runnable {

    @Override
    public void run() {

    }
}

public class App {

    private Runnable _runnable_1 = new Runnable() {
        @Override
        public void run() {
            System.out.println("runnable 1");
        }
    };

    private Runnable _runnable_2 = new TempRunnable() {
        @Override
        public void run() {
            System.out.print("runnable 2");
        }
    };

    public App() {
        System.out.println("empty init");
    }

    public App(int i) {
        System.out.println("1 param init");
    }

    public App(String str) {
        this();
        System.out.println("cascading init");
    }

    public static void main(String args[]) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);

        AsyncTaskCtx ctx = AsyncTaskCtx.newTask("test-task");

        Future<?> f1 = executor.submit(() -> new RunnableDemo("Thread-1").run());
        Future<?> f2 = executor.submit(() -> {
            executor.submit(() -> {
                System.out.println("task submitted from a worker thread");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                System.out.println("submitted task finished");
            });
            new RunnableDemo("Thread-2").run();
        });

        try {
            f1.get();
            f2.get();
        } finally {
            ctx.complete();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println(ctx.toString());
        }
    }
}