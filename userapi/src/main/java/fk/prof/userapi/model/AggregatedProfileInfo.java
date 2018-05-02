package fk.prof.userapi.model;

import fk.prof.idl.Profile;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author gaurav.ashok
 */
public class AggregatedProfileInfo {

    private final Profile.Header header;
    private final Profile.TraceCtxNames traceNames;
    private final Profile.TraceCtxDetailList traceCtxDetailList;
    private final List<Profile.ProfileWorkInfo> profiles;

    private final Map<String, AggregatedSamplesPerTraceCtx> aggregatedSamples;

    public AggregatedProfileInfo(Profile.Header header, Profile.TraceCtxNames traceNames,
                                 Profile.TraceCtxDetailList traceCtxDetailList,
                                 List<Profile.ProfileWorkInfo> profiles,
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

    public Iterable<String> getTraces() {
        return traceNames.getNameList();
    }

    public Iterable<Profile.TraceCtxDetail> getTraceDetails() {
        return traceCtxDetailList.getTraceCtxList();
    }

    public Iterable<Profile.ProfileWorkInfo> getProfiles() {
        return profiles;
    }

    public AggregatedSamplesPerTraceCtx getAggregatedSamples(String traceCtxName) {
        return aggregatedSamples.get(traceCtxName);
    }
}
