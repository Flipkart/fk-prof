package fk.prof;

public class TracingRunnable implements Runnable {

    private final Runnable target;
    private final AsyncTaskCtx taskCtx;

    private TracingRunnable(Runnable target, AsyncTaskCtx taskCtx) {
        this.target = target;
        this.taskCtx = taskCtx;
    }

    public static Runnable wrap(Runnable target) {
        Thread currentThd = Thread.currentThread();
        AsyncTaskCtx currentRunningTask = ThreadAccessor.getTaskCtx(currentThd);
        if(currentRunningTask == null) {
            return target;
        }

        AsyncTaskCtx newTaskCtx = currentRunningTask.addTask("java.");
        return new TracingRunnable(target, newTaskCtx);
    }

    @Override
    public void run() {
        Thread currThd = Thread.currentThread();
        AsyncTaskCtx previousCtx = ThreadAccessor.getTaskCtx(currThd);
        ThreadAccessor.setTaskCtx(currThd, taskCtx);
        taskCtx.start();

        try {
            if(target != null) {
                target.run();
            }
        } finally {
            taskCtx.end();
            ThreadAccessor.setTaskCtx(currThd, previousCtx);
        }
    }
}
