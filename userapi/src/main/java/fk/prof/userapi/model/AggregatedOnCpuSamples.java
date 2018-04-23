package fk.prof.userapi.model;

import fk.prof.userapi.model.tree.CallTree;

/**
 * @author gaurav.ashok
 */
public class AggregatedOnCpuSamples implements AggregatedSamples {

    private CallTree callTree;

    public AggregatedOnCpuSamples(CallTree callTree) {
        this.callTree = callTree;
    }

    public CallTree getCallTree() {
        return callTree;
    }
}
