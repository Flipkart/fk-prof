package fk.prof.trace;

public class IOTrace {

    /**
     * Threshold in nanoseconds.
     * Blocking IO calls that takes more time than this threshold shall be recorded.
     */
    private static volatile long latencyThreshold = Long.MAX_VALUE;

    public static long getLatencyThresholdNanos() {
        return latencyThreshold;
    }

    static void setLatencyThresholdNanos(long threshold) {
        latencyThreshold = threshold;
    }

    public static class Socket {

        public static native void accept(int fd, String address, long ts, long elapsed);

        public static native void connect(int fd, String address, long ts, long elapsed);

        public static native void read(int fd, int count, long ts, long elapsed);

        public static native void write(int fd, int count, long ts, long elapsed);

        // TODO finalize signature for select
    }

    public static class File {

        public static native void open(int fd, String path, long ts, long elapsed);

        public static native void read(int fd, int count, long ts, long elapsed);

        public static native void write(int fd, int count, long ts, long elapsed);
    }
}
