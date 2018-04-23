package fk.prof.userapi.api;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.common.io.ByteStreams;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowSerializer;
import fk.prof.aggregation.model.AggregationWindowSummarySerializer;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.ProcessGroupTag;
import fk.prof.metrics.Util;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.buffer.StorageBackedInputStream;
import fk.prof.userapi.model.*;
import fk.prof.userapi.model.tree.CallTree;
import fk.prof.userapi.util.ProtoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;
import java.util.zip.GZIPInputStream;

/**
 * Fetches aggregated profiles from the provided {@link AsyncStorage} storage.
 * @author gaurav.ashok
 */
public class StorageBackedProfileLoader implements ProfileLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageBackedProfileLoader.class);

    private final Meter profileLoadFailureCounter = Util.meter(MetricName.Profile_Load_Failures.get());
    private final Meter profileSummaryLoadFailureCounter = Util.meter(MetricName.ProfileSummary_Load_Failures.get());

    private final AsyncStorage asyncStorage;

    public StorageBackedProfileLoader(AsyncStorage asyncStorage) {
        this.asyncStorage = asyncStorage;
    }

    public AggregatedProfileInfo load(AggregatedProfileNamingStrategy filename) throws IOException {
        if(filename.version != AggregationWindowSerializer.VERSION) {
            throw new DeserializationException("file format version is not supported");
        }

        InputStream in = new StorageBackedInputStream(asyncStorage, filename);

        ProcessGroupTag pgTag = new ProcessGroupTag(filename.appId, filename.clusterId, filename.procId);
        Timer.Context timerCtx = Util.timer(MetricName.Profile_Load_Time_Prefix.get(), pgTag.toString()).time();

        try {
            in = new GZIPInputStream(in);
            return loadFromInputStream(filename, in);
        }
        catch (IOException e) {
            profileLoadFailureCounter.mark();
            throw e;
        }
        finally {
            timerCtx.stop();
            try {
                in.close();
            }
            catch (IOException e) {
                LOGGER.error("Failed to close istream. ", e);
            }
        }
    }

    public AggregationWindowSummary loadSummary(AggregatedProfileNamingStrategy filename) throws IOException {
        if(filename.version != AggregationWindowSummarySerializer.VERSION) {
            throw new DeserializationException("file format version is not supported");
        }

        if(!filename.isSummaryFile) {
            throw new IllegalArgumentException("filename is not for a summaryFile");
        }

        InputStream in = new StorageBackedInputStream(asyncStorage, filename);

        ProcessGroupTag pgTag = new ProcessGroupTag(filename.appId, filename.clusterId, filename.procId);
        Timer.Context timerCtx = Util.timer(MetricName.ProfileSummary_Load_Time_Prefix.get(), pgTag.toString()).time();

        try {
            in = new GZIPInputStream(in);
            return loadSummaryFromInputStream(in);
        }
        catch (IOException e) {
            profileSummaryLoadFailureCounter.mark();
            throw e;
        }
        finally {
            timerCtx.stop();
            try {
                in.close();
            }
            catch (IOException e) {
                LOGGER.error("Failed to close istream. ", e);
            }
        }
    }

    // leaving it as protected so that logic can be directly tested.
    static protected AggregatedProfileInfo loadFromInputStream(AggregatedProfileNamingStrategy filename,
                                                        InputStream in) throws IOException {
        Adler32 checksum = new Adler32();

        CheckedInputStream cin = new CheckedInputStream(in, checksum);

        int magicNum = ProtoUtil.readVariantInt32(cin);

        if (magicNum != AggregationWindowSerializer.AGGREGATION_FILE_MAGIC_NUM) {
            throw new DeserializationException("Unknown file. Unexpected first 4 bytes");
        }

        // read header
        AggregatedProfileModel.Header parsedHeader = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.Header.parser(), cin, "header");

        // read traceCtx list
        AggregatedProfileModel.TraceCtxNames traceNames = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.TraceCtxNames.parser(), cin, "traceNames");
        AggregatedProfileModel.TraceCtxDetailList traceDetails = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.TraceCtxDetailList.parser(), cin, "traceDetails");

        // read profiles summary
        checksumReset(checksum);
        List<AggregatedProfileModel.ProfileWorkInfo> profiles = new ArrayList<>();
        int size = 0;
        while ((size = ProtoUtil.readVariantInt32(cin)) != 0) {
            profiles.add(AggregatedProfileModel.ProfileWorkInfo.parseFrom(ByteStreams.limit(cin, size)));
        }
        checksumVerify((int) checksum.getValue(), ProtoUtil.readVariantInt32(in), "checksum error profileWorkInfo");

        // read method lookup table
        AggregatedProfileModel.MethodLookUp methodLookUp = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.MethodLookUp.parser(), cin, "methodLookup");

        // read work specific samples
        Map<String, AggregatedSamplesPerTraceCtx> samplesPerTrace = new HashMap<>();

        checksumReset(checksum);
        switch (filename.workType) {
            case cpu_sample_work:
                for (String traceName : traceNames.getNameList()) {
                    samplesPerTrace.put(traceName,
                            new AggregatedSamplesPerTraceCtx(methodLookUp, new AggregatedOnCpuSamples(CallTree.parseFrom(cin))));
                }
                break;
            default:
                break;
        }

        checksumVerify((int) checksum.getValue(), ProtoUtil.readVariantInt32(in), "checksum error " + filename.workType.name() + " aggregated samples");

        AggregatedProfileInfo profileInfo = new AggregatedProfileInfo(parsedHeader, traceNames, traceDetails, profiles, samplesPerTrace);

        return profileInfo;
    }

    // leaving it as protected so that logic can be directly tested.
    static protected AggregationWindowSummary loadSummaryFromInputStream(InputStream in) throws IOException {
        Adler32 checksum = new Adler32();
        CheckedInputStream cin = new CheckedInputStream(in, checksum);

        int magicNum = ProtoUtil.readVariantInt32(cin);

        if (magicNum !=  AggregationWindowSummarySerializer.SUMMARY_FILE_MAGIC_NUM) {
            throw new DeserializationException("Unknown file. Unexpected first 4 bytes");
        }

        // read header
        AggregatedProfileModel.Header parsedHeader = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.Header.parser(), cin, "header");

        // read traceCtx list
        AggregatedProfileModel.TraceCtxNames traceNames = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.TraceCtxNames.parser(), cin, "traceNames");

        // read profiles summary
        checksumReset(checksum);
        List<AggregatedProfileModel.ProfileWorkInfo> profiles = new ArrayList<>();
        int size = 0;
        while((size = ProtoUtil.readVariantInt32(cin)) != 0) {
            profiles.add(AggregatedProfileModel.ProfileWorkInfo.parseFrom(ByteStreams.limit(cin, size)));
        }
        checksumVerify((int)checksum.getValue(), ProtoUtil.readVariantInt32(in), "checksum error profileWorkInfo");

        // read work specific samples
        Map<AggregatedProfileModel.WorkType, AggregationWindowSummary.WorkSpecificSummary> summaryPerTrace = new HashMap<>();

        // cpu_sampling
        AggregatedProfileModel.TraceCtxDetailList traceDetails = ProtoUtil.buildProtoFromCheckedInputStream(AggregatedProfileModel.TraceCtxDetailList.parser(), cin, "cpu_sample traceDetails");
        summaryPerTrace.put(AggregatedProfileModel.WorkType.cpu_sample_work, new AggregationWindowSummary.CpuSampleSummary(traceDetails));

        AggregationWindowSummary summary = new AggregationWindowSummary(parsedHeader, traceNames, profiles, summaryPerTrace);

        return summary;
    }

    static private void checksumReset(Checksum checksum) {
        checksum.reset();
    }

    static private void checksumVerify(int actualChecksum, int expectedChecksum, String msg) {
        assert actualChecksum == expectedChecksum : msg;
    }
}
