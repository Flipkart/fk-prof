package fk.prof.userapi.model;

import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * @author gaurav.ashok
 */
public class AggregationWindowSummary {
    private final Profile.Header header;
    private final Profile.TraceCtxNames traceNames;
    private final List<Profile.ProfileWorkInfo> profiles;
    private final Map<WorkEntities.WorkType, WorkSpecificSummary> wsSummary;

    public AggregationWindowSummary(Profile.Header header, Profile.TraceCtxNames traceNames,
                                    List<Profile.ProfileWorkInfo> profiles,
                                    Map<WorkEntities.WorkType, WorkSpecificSummary> wsSummary) {
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

    public Iterable<Profile.ProfileWorkInfo> getProfiles() {
        return profiles;
    }

    public Map<WorkEntities.WorkType, WorkSpecificSummary> getWsSummary() {
        return wsSummary;
    }

    public static abstract class WorkSpecificSummary {
    }

    public static class CpuSampleSummary extends WorkSpecificSummary {
        private Profile.TraceCtxDetailList traceDetails;

        public CpuSampleSummary(Profile.TraceCtxDetailList traceDetails) {
            this.traceDetails = traceDetails;
        }

        public Iterable<Profile.TraceCtxDetail> getTraces() {
            return traceDetails.getTraceCtxList();
        }
    }
}
