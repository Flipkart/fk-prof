package fk.prof.userapi.model;

import fk.prof.idl.Profile;
import java.util.List;

/**
 * @author gaurav.ashok
 */
public class AggregatedIOTraceData implements AggregatedSamples {
    private List<Profile.IOSource> ioSources;
    private StacktraceTreeIterable stacktraceTree;

    public AggregatedIOTraceData(List<Profile.IOSource> ioSources, StacktraceTreeIterable stacktraceTree) {
        this.ioSources = ioSources;
        this.stacktraceTree = stacktraceTree;
    }

    public List<Profile.IOSource> getIoSources() {
        return this.ioSources;
    }

    public StacktraceTreeIterable getFrameNodes() {
        return stacktraceTree;
    }
}
