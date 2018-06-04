import fk.prof.AsyncTaskCtx;

class RunnableDemo implements Runnable {
    private Thread t;
    private String threadName;

    RunnableDemo(String name) {
        threadName = name;
        System.out.println("Creating " + threadName);
    }

    public void run() {
        System.out.println("Running " + threadName);
        try {
            System.out.println("Thread: " + threadName);
            // Let the thread sleep for a while.
            Thread.sleep(200);
        } catch (InterruptedException e) {
            System.out.println("Thread " + threadName + " interrupted.");
        }
        System.out.println("Thread " + threadName + " exiting.");
    }

    public void start() {
        System.out.println("Starting " + threadName);
        if (t == null) {
            t = new Thread(this, threadName);
            t.start();
        }
    }

    public void join() throws InterruptedException {
        t.join();
    }
}

public class App {
    public static void main(String args[]) throws InterruptedException {
        AsyncTaskCtx ctx = AsyncTaskCtx.newTask("test-task");

        System.out.println();
        RunnableDemo R1 = new RunnableDemo("Thread-1");
        R1.start();

        RunnableDemo R2 = new RunnableDemo("Thread-2");
        R2.start();

        try {
            R1.join();
            R2.join();
        } finally {
            ctx.complete();
        }
    }
}
