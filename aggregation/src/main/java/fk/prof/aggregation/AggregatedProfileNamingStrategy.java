package fk.prof.aggregation;

import com.amazonaws.util.StringUtils;
import com.google.common.io.BaseEncoding;
import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;
import fk.prof.storage.FileNamingStrategy;

import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * @author gaurav.ashok
 */
public class AggregatedProfileNamingStrategy implements FileNamingStrategy {

    private static final String DELIMITER = "/";
    private static final String FILE_FORMAT  = "%s/v%04d/%s/%s/%s/%s/%d/%s";

    public final String baseDir;
    public final int version;
    public final String appId;
    public final String clusterId;
    public final String procId;
    public final ZonedDateTime startTime;
    public final int duration;
    public final WorkEntities.WorkType workType;

    public final boolean isSummaryFile;

    private final String fileNamePrefix;

    public AggregatedProfileNamingStrategy(String baseDir, int version, String appId, String clusterId, String procId, ZonedDateTime startTime, int duration, WorkEntities.WorkType workType) {
        this.baseDir = baseDir;
        this.version = version;
        this.appId = appId;
        this.clusterId = clusterId;
        this.procId = procId;
        this.startTime = startTime;
        this.duration = duration;
        this.workType = workType;
        this.isSummaryFile = false;

        fileNamePrefix = String.format(FILE_FORMAT, baseDir, version, encode32(appId), encode32(clusterId),
                encode32(procId), startTime, duration, workType.name());
    }

    public AggregatedProfileNamingStrategy(String baseDir, int version, String appId, String clusterId, String procId, ZonedDateTime startTime, int duration) {
        this.baseDir = baseDir;
        this.version = version;
        this.appId = appId;
        this.clusterId = clusterId;
        this.procId = procId;
        this.startTime = startTime;
        this.duration = duration;
        this.workType = null;
        this.isSummaryFile = true;

        fileNamePrefix = String.format(FILE_FORMAT, baseDir, version, encode32(appId), encode32(clusterId),
                encode32(procId), startTime, duration, "summary");
    }

    public static AggregatedProfileNamingStrategy fromHeader(String baseDir, Profile.Header header) {
        if(header.hasWorkType()) {
            return new AggregatedProfileNamingStrategy(baseDir, header.getFormatVersion(), header.getAppId(), header.getClusterId(), header.getProcId(),
                    ZonedDateTime.parse(header.getAggregationStartTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME),
                    getDurationFromHeader(header), header.getWorkType());
        }
        else {
            return new AggregatedProfileNamingStrategy(baseDir, header.getFormatVersion(), header.getAppId(), header.getClusterId(), header.getProcId(),
                    ZonedDateTime.parse(header.getAggregationStartTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME),
                    getDurationFromHeader(header));
        }
    }

    @Override
    public String getFileName(int part) {
        return fileNamePrefix + String.format("/%04d", part);
    }

    public static AggregatedProfileNamingStrategy fromFileName(String path) {
        if(StringUtils.isNullOrEmpty(path)) {
            throw new IllegalArgumentException();
        }
        String[] tokens = path.split(DELIMITER);

        if("summary".equals(tokens[7])) {
            return new AggregatedProfileNamingStrategy(tokens[0], Integer.parseInt(tokens[1].substring(1)), decode32(tokens[2]), decode32(tokens[3]), decode32(tokens[4]),
                    ZonedDateTime.parse(tokens[5], DateTimeFormatter.ISO_ZONED_DATE_TIME), Integer.parseInt(tokens[6]));
        }

        return new AggregatedProfileNamingStrategy(tokens[0], Integer.parseInt(tokens[1].substring(1)), decode32(tokens[2]), decode32(tokens[3]), decode32(tokens[4]),
                ZonedDateTime.parse(tokens[5], DateTimeFormatter.ISO_ZONED_DATE_TIME), Integer.parseInt(tokens[6]),
                WorkEntities.WorkType.valueOf(tokens[7]));
    }

    private static String encode32(String str) {
        return BaseEncoding.base32().encode(str.getBytes(Charset.forName("utf-8")));
    }

    private static String decode32(String str) {
        return new String(BaseEncoding.base32().decode(str), Charset.forName("utf-8"));
    }

    private static int getDurationFromHeader(Profile.Header header) {
        ZonedDateTime startDateTime = ZonedDateTime.parse(header.getAggregationStartTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        ZonedDateTime endDateTime = ZonedDateTime.parse(header.getAggregationEndTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return (int)startDateTime.until(endDateTime, ChronoUnit.SECONDS);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AggregatedProfileNamingStrategy that = (AggregatedProfileNamingStrategy) o;

        return fileNamePrefix.equals(that.fileNamePrefix);
    }

    @Override
    public int hashCode() {
        return fileNamePrefix.hashCode();
    }

    @Override
    public String toString() {
        return fileNamePrefix;
    }
}
