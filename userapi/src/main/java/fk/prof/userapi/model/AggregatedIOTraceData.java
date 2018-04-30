package fk.prof.userapi.model;

/**
 * @author gaurav.ashok
 */
public class AggregatedIOTraceData implements AggregatedSamples {

    private StacktraceTreeIterable stacktraceTree;

    public AggregatedIOTraceData(StacktraceTreeIterable stacktraceTree) {
        this.stacktraceTree = stacktraceTree;
    }

    public StacktraceTreeIterable getFrameNodes() {
        return stacktraceTree;
    }
}
