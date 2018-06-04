package fk.prof;

public class WrappedThreadRunnable implements Runnable {

    private final Runnable target;
    private final AsyncTaskCtx taskCtx;

    public WrappedThreadRunnable(Runnable target, AsyncTaskCtx taskCtx) {
        this.target = target;
        this.taskCtx = taskCtx;
    }

    @Override
    public void run() {
        if(taskCtx != null) {
            taskCtx.start();
        }

        try {
            if(target != null) {
                target.run();
            }
        } finally {
            if(taskCtx != null) {
                taskCtx.end();
            }
        }
    }
}
