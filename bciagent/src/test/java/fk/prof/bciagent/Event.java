package fk.prof.bciagent;

public class Event {
    public final String caller;
    public final long evt_created;
    public final long ts_ms;
    public final long elapsed_ns;
    public final int fd;
    public final Object data;

    @Override
    public String toString() {
        return "{ \"method\" : \"" + caller + "\"," +
                " \"ts\" : " + ts_ms + "," +
                " \"evt_created\" : " + evt_created + "," +
                " \"elapsed\" : " + elapsed_ns + "," +
                " \"fd\" : " + fd + "," +
                " \"" + (data instanceof Socket ? "socket" : "file") + "\" : " + data.toString() +
                " }";
    }

    public Event(String caller, long ts_ms, long elapsed_ns, int fd, Socket socket) {
        this.caller = caller;
        this.ts_ms = ts_ms;
        this.elapsed_ns = elapsed_ns;
        this.fd = fd;
        this.data = socket;
        this.evt_created = System.nanoTime();
    }

    public Event(String caller, long ts_ms, long elapsed_ns, int fd, File file) {
        this.caller = caller;
        this.ts_ms = ts_ms;
        this.elapsed_ns = elapsed_ns;
        this.fd = fd;
        this.data = file;
        this.evt_created = System.nanoTime();
    }

    public static boolean equals(Event e1, Event e2) {
        return e1.equals(e2);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Event event = (Event) o;

        if (fd != event.fd) return false;
        return data != null ? data.equals(event.data) : event.data == null;
    }

    @Override
    public int hashCode() {
        int result = fd;
        result = 31 * result + (data != null ? data.hashCode() : 0);
        return result;
    }

    public static class Socket {
        public enum EvtType {
            connect, accept, read, write;
        }

        public final EvtType type;
        public final String remotePath;
        public final long count;
        public final boolean timedout;

        private Socket(EvtType type, String remotePath, long count, boolean timedout) {
            this.type = type;
            this.remotePath = remotePath;
            this.count = count;
            this.timedout = timedout;
        }

        public static Socket connect(String path) {
            return new Socket(EvtType.connect, path, -1, false);
        }

        public static Socket accept(String path) {
            return new Socket(EvtType.accept, path, -1, false);
        }

        public static Socket read(long count, boolean timedout) {
            return new Socket(EvtType.read, null, count, timedout);
        }

        public static Socket write(long count) {
            return new Socket(EvtType.write, null, count, false);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Socket socket = (Socket) o;

            if (count != socket.count) return false;
            if (timedout != socket.timedout) return false;
            if (type != socket.type) return false;
            return remotePath != null ? remotePath.equals(socket.remotePath) : socket.remotePath == null;
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + (remotePath != null ? remotePath.hashCode() : 0);
            result = 31 * result + (int)(count >> 32) ^ ((int)count);
            result = 31 * result + (timedout ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            switch (type) {
                case accept:
                case connect:
                    return "\"" + type.name() + "(" + remotePath + ")\"";
                case read:
                    return "\"read(" + count + "," + timedout + ")\"";
                case write:
                    return "\"write(" + count + ")\"";
            }
            return "";
        }
    }

    public static class File {

        public enum EvtType {
            open, read,write;
        }

        public final EvtType type;
        public final String path;
        public final long count;

        private File(EvtType type, String path, long count) {
            this.type = type;
            this.path = path;
            this.count = count;
        }

        public static File open(String path) {
            return new File(EvtType.open, path, -1);
        }

        public static File read(long count) {
            return new File(EvtType.read, null, count);
        }

        public static File write(long count) {
            return new File(EvtType.write, null, count);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            File file = (File) o;

            if (count != file.count) return false;
            if (type != file.type) return false;
            return path != null ? path.equals(file.path) : file.path == null;
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + (path != null ? path.hashCode() : 0);
            result = 31 * result + (int)(count >> 32) ^ ((int)count);
            return result;
        }

        @Override
        public String toString() {
            switch (type) {
                case open:
                    return "\"open(" + path + ")\"";
                case read:
                case write:
                    return "\"" + type.name() + "(" + count + ")\"";
            }

            return "";
        }
    }
}
