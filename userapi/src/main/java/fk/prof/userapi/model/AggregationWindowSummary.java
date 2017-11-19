package fk.prof.userapi.model;

import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.cache.Cacheable;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * @author gaurav.ashok
 */
public class AggregationWindowSummary implements Cacheable<AggregationWindowSummary> {
    private final AggregatedProfileModel.Header header;
    private final AggregatedProfileModel.TraceCtxNames traceNames;
    private final List<AggregatedProfileModel.ProfileWorkInfo> profiles;
    private final Map<AggregatedProfileModel.WorkType, WorkSpecificSummary> wsSummary;

    public AggregationWindowSummary(AggregatedProfileModel.Header header, AggregatedProfileModel.TraceCtxNames traceNames,
                                    List<AggregatedProfileModel.ProfileWorkInfo> profiles,
                                    Map<AggregatedProfileModel.WorkType, WorkSpecificSummary> wsSummary) {
        this.header = header;
        this.traceNames = traceNames;
        this.profiles = profiles;
        this.wsSummary = wsSummary;
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

    public Iterable<AggregatedProfileModel.ProfileWorkInfo> getProfiles() {
        return profiles;
    }

    public Map<AggregatedProfileModel.WorkType, WorkSpecificSummary> getWsSummary() {
        return wsSummary;
    }

    public static abstract class WorkSpecificSummary {
    }

    public static class CpuSampleSummary extends WorkSpecificSummary {
        private AggregatedProfileModel.TraceCtxDetailList traceDetails;

        public CpuSampleSummary(AggregatedProfileModel.TraceCtxDetailList traceDetails) {
            this.traceDetails = traceDetails;
        }

        public Iterable<AggregatedProfileModel.TraceCtxDetail> getTraces() {
            return traceDetails.getTraceCtxList();
        }
    }
}
