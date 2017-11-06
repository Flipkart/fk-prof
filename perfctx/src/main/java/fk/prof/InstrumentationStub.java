package fk.prof;

/**
 * @understands patching instrumented-site to recorder
 *
 * As a user of fk-prof, one should never interact with this class directly.
 */
@SuppressWarnings("unused")
public class InstrumentationStub {
    private static boolean engaged = false;

    private InstrumentationStub() {
    }

    private static native void evtReturn();

    private static native void fsMethodExit(long elapsed, int fd, String filename);

    @SuppressWarnings("unused")
    public static void returnTracepoint(int var0, int var1) {
        if (engaged) {
            evtReturn();
        }
    }

    @SuppressWarnings("unused")
    public static void fsOpEndTracepoint(long elapsed, String filename, int fd) {
        System.out.println("fsOpEndTracepoint method=" +  Thread.currentThread().getStackTrace()[2].getMethodName() + ", elapsed=" + elapsed + ", filename=" + filename + ", fd=" + fd);
        fsMethodExit(elapsed, fd, filename);
    }

    @SuppressWarnings("unused")
    public static void entryTracepoint() {
        System.out.println("Method ENTRY: " + Thread.currentThread().getStackTrace()[2].getMethodName());
    }

    @SuppressWarnings("unused")
    public static void exitTracepoint() {
        System.out.println("Method EXIT: " + Thread.currentThread().getStackTrace()[2].getMethodName());
    }

}

