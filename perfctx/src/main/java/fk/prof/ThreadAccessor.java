package fk.prof;

import fk.prof.AsyncTaskCtx;

import java.lang.reflect.Field;

public class ThreadAccessor {

    private static final Field taskCtxField;

    private static boolean initialized = false;

    static {
        Field f = null;
        try {
            f = Thread.class.getDeclaredField("taskCtx");
            initialized = true;
        }
        catch (NoSuchFieldException e) {
        } finally {
            taskCtxField = f;
        }
    }

    public static AsyncTaskCtx getTaskCtx(Thread thd) {
        try {
            return (AsyncTaskCtx) taskCtxField.get(thd);
        } catch (Exception e) {
            throw new IllegalStateException("taskCtx field should have been accessible");
        }
    }

    public static void setTaskCtx(Thread thd, AsyncTaskCtx taskCtx) {
        try {
            taskCtxField.set(thd, taskCtx);
        } catch (Exception e) {
            throw new IllegalStateException("taskCtx field should have been accessible");
        }
    }
}
