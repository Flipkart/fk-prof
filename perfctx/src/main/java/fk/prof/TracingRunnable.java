package fk.prof;

public class TracingRunnable implements Runnable {

    public final AsyncTaskCtx taskCtx;
    private final Runnable target;

    private Throwable erroredWith;
    private boolean started = false;

    private Thread executingThread;
    private AsyncTaskCtx invokingTaskCtx;

    private TracingRunnable(Runnable target, AsyncTaskCtx taskCtx) {
        this.target = target;
        this.taskCtx = taskCtx;
    }

    public static Runnable wrap(Runnable target) {
        if(target == null)
            return null;

        Thread currentThd = Thread.currentThread();
        AsyncTaskCtx currentRunningTask = ThreadAccessor.getTaskCtx(currentThd);
        if(currentRunningTask == null) {
            return target;
        }

        AsyncTaskCtx newTaskCtx = currentRunningTask.addTask("java.");
        return new TracingRunnable(target, newTaskCtx);
    }

    public void setExecutingThread(Thread executingThread) {
        this.executingThread = executingThread;
    }

    public void beforeExecute(Thread executingThread) {
        this.executingThread = executingThread;

        invokingTaskCtx = ThreadAccessor.getTaskCtx(executingThread);
        ThreadAccessor.setTaskCtx(executingThread, taskCtx);
        taskCtx.start(this.executingThread);
        started = true;
    }

    public void afterExecute(Throwable thrown) {
        erroredWith = thrown;
        taskCtx.end();
        ThreadAccessor.setTaskCtx(executingThread, invokingTaskCtx);
        invokingTaskCtx = null;
        executingThread = null;
        started = false;
    }

    @Override
    public void run() {
        boolean managed = true;
        if(!started) {
            managed = false;
            beforeExecute(executingThread == null ? Thread.currentThread() : executingThread);
        }

        Throwable thrown = null;
        try {
            if(target != null) {
                target.run();
            }
        } catch (RuntimeException x) {
            thrown = x; throw x;
        } catch (Error x) {
            thrown = x; throw x;
        } catch (Throwable x) {
            thrown = x;
            throw new Error(x);
        } finally {
            if(!managed) {
                afterExecute(thrown);
            }
        }
    }
}
