package fk.prof.trace;

import fk.prof.FdAccessor;

import java.io.FileDescriptor;

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

        public static native void _accept(int fd, String address, long ts, long elapsed);

        public static void accept(FileDescriptor fd, String address, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _accept(FdAccessor.getFd(fd), address, System.currentTimeMillis(), elapsed);
            }
        }

        public static native void _connect(int fd, String address, long ts, long elapsed);

        public static void connect(FileDescriptor fd, String address, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _connect(FdAccessor.getFd(fd), address, System.currentTimeMillis(), elapsed);
            }
        }

        public static native void _read(int fd, int count, long ts, long elapsed, boolean timeout);

        public static void read(FileDescriptor fd, int count, long elapsed, boolean timeout) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _read(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed, timeout);
            }
        }

        public static void read(int fd, int count, long elapsed, boolean timeout) {
            if (elapsed >= latencyThreshold && fd >= 0) {
                _read(fd, count, System.currentTimeMillis(), elapsed, timeout);
            }
        }

        public static native void _write(int fd, int count, long ts, long elapsed);

        public static void write(FileDescriptor fd, int count, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _write(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed);
            }
        }

        public static void write(int fd, int count, long elapsed) {
            if (elapsed >= latencyThreshold && fd >= 0) {
                _write(fd, count, System.currentTimeMillis(), elapsed);
            }
        }
    }

    public static class File {

        public static native void _open(int fd, String path, long ts, long elapsed);

        public static void open(FileDescriptor fd, String path, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _open(FdAccessor.getFd(fd), path, System.currentTimeMillis(), elapsed);
            }
        }

        public static native void _read(int fd, int count, long ts, long elapsed);

        public static void read(FileDescriptor fd, int count, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _read(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed);
            }
        }

        public static native void _write(int fd, int count, long ts, long elapsed);

        public static void write(FileDescriptor fd, int count, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _write(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed);
            }
        }
    }
}
