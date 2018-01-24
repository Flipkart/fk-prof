package fk.prof.bciagent;

import fk.prof.bciagent.tracer.IOTracer;

import java.io.FileDescriptor;
import java.util.List;

public class MockFileTracer extends IOTracer.FileOpTracer {

    private List<Event> events;

    public MockFileTracer(List<Event> events) {
        this.events = events;
    }

    @Override
    public void open(FileDescriptor fd, String path, long elapsed) {
        events.add(new Event(Util.getCallerName(), System.currentTimeMillis(), elapsed, fd.valid() ? FdAccessor.getFd(fd) : -1, Event.File.open(path)));
    }

    @Override
    public void read(FileDescriptor fd, long count, long elapsed) {
        events.add(new Event(Util.getCallerName(), System.currentTimeMillis(), elapsed, fd.valid() ? FdAccessor.getFd(fd) : -1, Event.File.read(count)));
    }

    @Override
    public void write(FileDescriptor fd, long count, long elapsed) {
        events.add(new Event(Util.getCallerName(), System.currentTimeMillis(), elapsed, fd.valid() ? FdAccessor.getFd(fd) : -1, Event.File.write(count)));
    }
}
