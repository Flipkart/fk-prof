package fk.prof.userapi.model;

import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.util.StackLineParser;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author gaurav.ashok
 */
public class AggregatedSamplesPerTraceCtx {

    private final AggregatedSamples aggregatedSamples;
    private final List<String> methodLookup;

    public AggregatedSamplesPerTraceCtx(AggregatedProfileModel.MethodLookUp methodIdlookup, AggregatedSamples aggregatedSamples) {
        this.aggregatedSamples = aggregatedSamples;
        this.methodLookup = methodIdlookup.getFqdnList().stream().map(StackLineParser::convertJVMTypeSignToJava).collect(Collectors.toList());
    }

    public AggregatedSamples getAggregatedSamples() {
        return aggregatedSamples;
    }

    public List<String> getMethodLookup() {
        return methodLookup;
    }
}
