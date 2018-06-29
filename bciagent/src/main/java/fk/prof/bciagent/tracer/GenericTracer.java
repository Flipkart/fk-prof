package fk.prof.bciagent.tracer;

public class GenericTracer {

    public static void trace(String msg, Object obj) {
        System.out.println(threadId() + msg + "; " + toStr(obj));
    }

    private static String threadId() {
        Thread thd = Thread.currentThread();
        return "[" + thd.getId() + "\t" + thd.getName() + "] ";
    }

    private static String toStr(Object obj) {
        return obj == null ? "NULL" : obj.toString();
    }
}
