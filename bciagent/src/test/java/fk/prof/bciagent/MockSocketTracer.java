package fk.prof.bciagent;

import fk.prof.bciagent.tracer.IOTracer;

import java.io.FileDescriptor;
import java.util.List;

public class MockSocketTracer extends IOTracer.SocketOpTracer {

    private List<Event> events;

    public MockSocketTracer(List<Event> events) {
        this.events = events;
    }

    @Override
    public void accept(FileDescriptor fd, String address, long elapsed) {
        this.accept(fd.valid() ? FdAccessor.getFd(fd) : -1, address, elapsed);
    }

    @Override
    public void accept(int fd, String address, long elapsed) {
        events.add(new Event(Util.getInstumentedCallerName(), System.currentTimeMillis(), elapsed, fd, Event.Socket.accept(address)));
    }

    @Override
    public void connect(FileDescriptor fd, String address, long elapsed) {
        this.connect(fd.valid() ? FdAccessor.getFd(fd) : -1, address, elapsed);
    }

    @Override
    public void connect(int fd, String address, long elapsed) {
        events.add(new Event(Util.getInstumentedCallerName(), System.currentTimeMillis(), elapsed, fd, Event.Socket.connect(address)));
    }

    @Override
    public void read(FileDescriptor fd, long count, long elapsed, boolean timeout) {
        events.add(new Event(Util.getInstumentedCallerName(), System.currentTimeMillis(), elapsed, fd.valid() ? FdAccessor.getFd(fd) : -1, Event.Socket.read(count, timeout)));
    }

    @Override
    public void write(FileDescriptor fd, long count, long elapsed) {
        events.add(new Event(Util.getInstumentedCallerName(), System.currentTimeMillis(), elapsed, fd.valid() ? FdAccessor.getFd(fd) : -1, Event.Socket.write(count)));
    }
}
