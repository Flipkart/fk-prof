package fk.prof.userapi.model;

import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Cacheable;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * @author gaurav.ashok
 */
public class AggregatedProfileInfo implements Cacheable {

    private final AggregatedProfileModel.Header header;
    private final AggregatedProfileModel.TraceCtxNames traceNames;
    private final AggregatedProfileModel.TraceCtxDetailList traceCtxDetailList;
    private final List<AggregatedProfileModel.ProfileWorkInfo> profiles;

    private final Map<String, AggregatedSamplesPerTraceCtx> aggregatedSamples;

    public AggregatedProfileInfo(AggregatedProfileModel.Header header, AggregatedProfileModel.TraceCtxNames traceNames,
                                 AggregatedProfileModel.TraceCtxDetailList traceCtxDetailList,
                                 List<AggregatedProfileModel.ProfileWorkInfo> profiles,
                                 Map<String, AggregatedSamplesPerTraceCtx> aggregatedSamples) {
        this.header = header;
        this.traceNames = traceNames;
        this.traceCtxDetailList = traceCtxDetailList;
        this.profiles = profiles;
        this.aggregatedSamples = aggregatedSamples;
    }

    public ZonedDateTime getStart() {
        return ZonedDateTime.parse(header.getAggregationStartTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    public int getDuration() {
        return header.getWindowDuration();
    }

    public String getAppId() {
        return header.getAppId();
    }

    public String getClusterId() {
        return header.getClusterId();
    }

    public String getProcId() {
        return header.getProcId();
    }

    public Iterable<String> getTraces() {
        return traceNames.getNameList();
    }

    public Iterable<AggregatedProfileModel.TraceCtxDetail> getTraceDetails() {
        return traceCtxDetailList.getTraceCtxList();
    }

    public Iterable<AggregatedProfileModel.ProfileWorkInfo> getProfiles() {
        return profiles;
    }

    public AggregatedSamplesPerTraceCtx getAggregatedSamples(String traceCtxName) {
        return aggregatedSamples.get(traceCtxName);
    }
}
