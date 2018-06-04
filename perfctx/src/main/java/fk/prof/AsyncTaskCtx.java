package fk.prof;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class AsyncTaskCtx {

    private final static AtomicLong idGen = new AtomicLong(0);

    private final static List<AsyncTaskCtx> emptyList = Collections.emptyList();

    private final String name;

    private final long id;

    private long start, end;

    private List<AsyncTaskCtx> children = emptyList;

    private AsyncTaskCtx(String name, long id) {
        this.name = name;
        this.id = id;
    }

    private AsyncTaskCtx(String name) {
        this(name, idGen.incrementAndGet());
    }

    public static AsyncTaskCtx newTask(String name) {
        Thread thd = Thread.currentThread();
        AsyncTaskCtx task;

        try {
            Field f = Thread.class.getDeclaredField("taskCtx");
            task = new AsyncTaskCtx(name);
            f.set(thd, task);
            task.start();
            return task;
        } catch (Exception e) {
            System.err.println("Totally not supposed to happen");
            e.printStackTrace();
        }

        // unreachable
        System.exit(-1);
        return null;
    }

    public void complete() {
        this.end();
        // print
        System.out.println("Task complete: " + name);
    }

    public AsyncTaskCtx addNamedTask(String name) {
        AsyncTaskCtx task = new AsyncTaskCtx(name, this.id);
        if(children == emptyList) {
            children = new LinkedList<>();
        }

        children.add(task);
        return task;
    }

    public AsyncTaskCtx addTask(String skipPackage) {
        AsyncTaskCtx task = new AsyncTaskCtx(getCallerSite(skipPackage), this.id);
        if(children == emptyList) {
            children = new LinkedList<>();
        }

        children.add(task);
        return task;
    }

    public static String getCallerSite(String skipPackage) {
        StackTraceElement[] st = new Exception().getStackTrace();
        if(st != null) {
            for(StackTraceElement e: st) {
                if(e.getClassName().startsWith(skipPackage) ||
                    e.getClassName().startsWith("fk.prof.AsyncTaskCtx"))
                    continue;
                return e.getClassName() + e.getMethodName() + " @ " + e.getLineNumber();
            }
        }
        return "()";
    }

    public String getName() {
        return name;
    }

    public long getId() {
        return id;
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public List<AsyncTaskCtx> getChildren() {
        return children;
    }

    public void start() {
        this.start = System.nanoTime();
    }

    public void end() {
        this.end = System.nanoTime();
    }
}
