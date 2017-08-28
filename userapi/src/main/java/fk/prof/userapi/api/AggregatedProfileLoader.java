package fk.prof.userapi.api;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.common.io.ByteStreams;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowSerializer;
import fk.prof.aggregation.model.AggregationWindowSummarySerializer;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.metrics.ProcessGroupTag;
import fk.prof.metrics.Util;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.buffer.StorageBackedInputStream;
import fk.prof.userapi.Deserializer;
import fk.prof.userapi.model.*;
import fk.prof.userapi.model.tree.CallTree;
import io.vertx.core.Future;

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
 * @author gaurav.ashok
 */
public class AggregatedProfileLoader {

    private static String profileLoadTimerPrefix = "profile.load.timer";
    private static String profileSummaryLoadTimerPrefix = "profilesummary.load.timer";

    private Meter profileLoadFailureCounter = Util.meter("profile.load.failures.meter");
    private Meter profileSummaryLoadFailureCounter = Util.meter("profilesummary.load.failures.meter");

    private AsyncStorage asyncStorage;

    public AggregatedProfileLoader(AsyncStorage asyncStorage) {
        this.asyncStorage = asyncStorage;
    }

    public void load(Future<AggregatedProfileInfo> future, AggregatedProfileNamingStrategy filename) {
        if(filename.version != AggregationWindowSerializer.VERSION) {
            future.fail("file format version is not supported");
            return;
        }

        InputStream in = new StorageBackedInputStream(asyncStorage, filename);

        ProcessGroupTag pgTag = new ProcessGroupTag(filename.appId, filename.clusterId, filename.procId);
        Timer.Context timerCtx = Util.timer(profileLoadTimerPrefix, pgTag.toString()).time();

        try {
            in = new GZIPInputStream(in);
            loadFromInputStream(future, filename, in);
        }
        catch (IOException e) {
            profileLoadFailureCounter.mark();
            future.fail(e);
        }
        finally {
            timerCtx.stop();
            try {
                in.close();
            }
            catch (IOException e) {
                // log the error
            }
        }
    }

    public void loadSummary(Future<AggregationWindowSummary> future, AggregatedProfileNamingStrategy filename) {
        if(filename.version != AggregationWindowSummarySerializer.VERSION) {
            future.fail("file format version is not supported");
            return;
        }

        if(!filename.isSummaryFile) {
            future.fail(new IllegalArgumentException("filename is not for a summaryFile"));
            return;
        }

        InputStream in = new StorageBackedInputStream(asyncStorage, filename);

        ProcessGroupTag pgTag = new ProcessGroupTag(filename.appId, filename.clusterId, filename.procId);
        Timer.Context timerCtx = Util.timer(profileSummaryLoadTimerPrefix, pgTag.toString()).time();

        try {
            in = new GZIPInputStream(in);
            loadSummaryFromInputStream(future, filename, in);
        }
        catch (IOException e) {
            profileSummaryLoadFailureCounter.mark();
            future.fail(e);
        }
        finally {
            timerCtx.stop();
            try {
                in.close();
            }
            catch (IOException e) {
                // log the error
            }
        }
    }

    // leaving it as protected so that logic can be directly tested.
    protected void loadFromInputStream(Future<AggregatedProfileInfo> future, AggregatedProfileNamingStrategy filename, InputStream in) {

        Adler32 checksum = new Adler32();
        try {
            CheckedInputStream cin = new CheckedInputStream(in, checksum);

            int magicNum = Deserializer.readVariantInt32(cin);

            if (magicNum != AggregationWindowSerializer.AGGREGATION_FILE_MAGIC_NUM) {
                future.fail("Unknown file. Unexpected first 4 bytes");
                return;
            }

            // read header
            AggregatedProfileModel.Header parsedHeader = Deserializer.readCheckedDelimited(AggregatedProfileModel.Header.parser(), cin, "header");

            // read traceCtx list
            AggregatedProfileModel.TraceCtxNames traceNames = Deserializer.readCheckedDelimited(AggregatedProfileModel.TraceCtxNames.parser(), cin, "traceNames");
            AggregatedProfileModel.TraceCtxDetailList traceDetails = Deserializer.readCheckedDelimited(AggregatedProfileModel.TraceCtxDetailList.parser(), cin, "traceDetails");

            // read profiles summary
            checksumReset(checksum);
            List<AggregatedProfileModel.ProfileWorkInfo> profiles = new ArrayList<>();
            int size = 0;
            while ((size = Deserializer.readVariantInt32(cin)) != 0) {
                profiles.add(AggregatedProfileModel.ProfileWorkInfo.parseFrom(ByteStreams.limit(cin, size)));
            }
            checksumVerify((int) checksum.getValue(), Deserializer.readVariantInt32(in), "checksum error profileWorkInfo");

            // read method lookup table
            AggregatedProfileModel.MethodLookUp methodLookUp = Deserializer.readCheckedDelimited(AggregatedProfileModel.MethodLookUp.parser(), cin, "methodLookup");

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

            checksumVerify((int) checksum.getValue(), Deserializer.readVariantInt32(in), "checksum error " + filename.workType.name() + " aggregated samples");

            AggregatedProfileInfo profileInfo = new AggregatedProfileInfo(parsedHeader, traceNames, traceDetails, profiles, samplesPerTrace);

            future.complete(profileInfo);
        }
        catch (IOException e) {
            profileLoadFailureCounter.mark();
            future.fail(e);
        }
    }

    // leaving it as protected so that logic can be directly tested.
    protected void loadSummaryFromInputStream(Future<AggregationWindowSummary> future, AggregatedProfileNamingStrategy filename, InputStream in) {
        Adler32 checksum = new Adler32();
        try {
            CheckedInputStream cin = new CheckedInputStream(in, checksum);

            int magicNum = Deserializer.readVariantInt32(cin);

            if (magicNum !=  AggregationWindowSummarySerializer.SUMMARY_FILE_MAGIC_NUM) {
                future.fail("Unknown file. Unexpected first 4 bytes");
                return;
            }

            // read header
            AggregatedProfileModel.Header parsedHeader = Deserializer.readCheckedDelimited(AggregatedProfileModel.Header.parser(), cin, "header");

            // read traceCtx list
            AggregatedProfileModel.TraceCtxNames traceNames = Deserializer.readCheckedDelimited(AggregatedProfileModel.TraceCtxNames.parser(), cin, "traceNames");

            // read profiles summary
            checksumReset(checksum);
            List<AggregatedProfileModel.ProfileWorkInfo> profiles = new ArrayList<>();
            int size = 0;
            while((size = Deserializer.readVariantInt32(cin)) != 0) {
                profiles.add(AggregatedProfileModel.ProfileWorkInfo.parseFrom(ByteStreams.limit(cin, size)));
            }
            checksumVerify((int)checksum.getValue(), Deserializer.readVariantInt32(in), "checksum error profileWorkInfo");

            // read work specific samples
            Map<AggregatedProfileModel.WorkType, AggregationWindowSummary.WorkSpecificSummary> summaryPerTrace = new HashMap<>();

            // cpu_sampling
            AggregatedProfileModel.TraceCtxDetailList traceDetails = Deserializer.readCheckedDelimited(AggregatedProfileModel.TraceCtxDetailList.parser(), cin, "cpu_sample traceDetails");
            summaryPerTrace.put(AggregatedProfileModel.WorkType.cpu_sample_work, new AggregationWindowSummary.CpuSampleSummary(traceDetails));

            AggregationWindowSummary summary = new AggregationWindowSummary(parsedHeader, traceNames, profiles, summaryPerTrace);

            future.complete(summary);
        }
        catch (IOException e) {
            profileSummaryLoadFailureCounter.mark();
            future.fail(e);
        }
    }

    private void checksumReset(Checksum checksum) {
        checksum.reset();
    }

    private void checksumVerify(int actualChecksum, int expectedChecksum, String msg) {
        assert actualChecksum == expectedChecksum : msg;
    }
}
