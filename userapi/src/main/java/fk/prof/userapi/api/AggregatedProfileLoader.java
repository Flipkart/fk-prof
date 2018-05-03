package fk.prof.userapi.api;


import com.google.common.io.ByteStreams;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowSerializer;
import fk.prof.aggregation.model.AggregationWindowSummarySerializer;
import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.buffer.StorageBackedInputStream;
import fk.prof.userapi.Deserializer;
import fk.prof.userapi.model.*;
import io.vertx.core.Future;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;
import java.util.zip.GZIPInputStream;

/**
 * @author gaurav.ashok
 */
public class AggregatedProfileLoader {

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

        try {
            in = new GZIPInputStream(in);
            loadFromInputStream(future, filename, in);
        }
        catch (IOException e) {
            future.fail(e);
        }
        finally {
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

        try {
            in = new GZIPInputStream(in);
            loadSummaryFromInputStream(future, filename, in);
        }
        catch (IOException e) {
            future.fail(e);
        }
        finally {
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
            Profile.Header parsedHeader = Deserializer.readCheckedDelimited(Profile.Header.parser(), cin, "header");

            // read traceCtx list
            Profile.TraceCtxNames traceNames = Deserializer.readCheckedDelimited(Profile.TraceCtxNames.parser(), cin, "traceNames");
            Profile.TraceCtxDetailList traceDetails = Deserializer.readCheckedDelimited(Profile.TraceCtxDetailList.parser(), cin, "traceDetails");

            // read profiles summary
            checksumReset(checksum);
            List<Profile.ProfileWorkInfo> profiles = new ArrayList<>();
            int size = 0;
            while ((size = Deserializer.readVariantInt32(cin)) != 0) {
                profiles.add(Profile.ProfileWorkInfo.parseFrom(ByteStreams.limit(cin, size)));
            }
            checksumVerify((int) checksum.getValue(), Deserializer.readVariantInt32(in), "checksum error profileWorkInfo");

            Profile.MethodLookUp methodLookUp;
            fk.prof.idl.Profile.IOSources ioSources;
            Map<String, AggregatedSamplesPerTraceCtx> samplesPerTrace = new HashMap<>();

            switch (filename.workType) {
                case cpu_sample_work:
                    methodLookUp = Deserializer.readCheckedDelimited(Profile.MethodLookUp.parser(), cin, "methodLookup");
                    checksumReset(checksum);
                    for (String traceName : traceNames.getNameList()) {
                        samplesPerTrace.put(traceName,
                                new AggregatedSamplesPerTraceCtx(methodLookUp, new AggregatedCpuSamplesData(parseStacktraceTree(cin))));
                    }
                    break;
                case io_trace_work:
                    methodLookUp = Deserializer.readCheckedDelimited(Profile.MethodLookUp.parser(), cin, "methodLookup");
                    ioSources = Deserializer.readCheckedDelimited(Profile.IOSources.parser(), cin, "ioSourcesLookup");
                    checksumReset(checksum);
                    for (String traceName : traceNames.getNameList()) {
                        samplesPerTrace.put(traceName,
                            new AggregatedSamplesPerTraceCtx(methodLookUp, new AggregatedIOTraceData(ioSources.getIoSourcesList(), parseStacktraceTree(cin))));
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
            Profile.Header parsedHeader = Deserializer.readCheckedDelimited(Profile.Header.parser(), cin, "header");

            // read traceCtx list
            Profile.TraceCtxNames traceNames = Deserializer.readCheckedDelimited(Profile.TraceCtxNames.parser(), cin, "traceNames");

            // read profiles summary
            checksumReset(checksum);
            List<Profile.ProfileWorkInfo> profiles = new ArrayList<>();
            int size;
            while((size = Deserializer.readVariantInt32(cin)) != 0) {
                profiles.add(Profile.ProfileWorkInfo.parseFrom(ByteStreams.limit(cin, size)));
            }
            checksumVerify((int)checksum.getValue(), Deserializer.readVariantInt32(in), "checksum error profileWorkInfo");

            // read work specific information
            Map<WorkEntities.WorkType, AggregationWindowSummary.WorkSpecificSummary> summaryPerTrace = new HashMap<>();
            List<WorkEntities.WorkType> wtypes = parsedHeader.getPolicy().getWorkList().stream().map(WorkEntities.Work::getWType).collect(Collectors.toList());
            for (WorkEntities.WorkType workType: wtypes) {
                summaryPerTrace.put(workType, parseWorkSpecificSummary(cin, workType));
            }

            AggregationWindowSummary summary = new AggregationWindowSummary(parsedHeader, traceNames, profiles, summaryPerTrace);
            future.complete(summary);
        }
        catch (IOException e) {
            future.fail(e);
        }
    }

    private void checksumReset(Checksum checksum) {
        checksum.reset();
    }

    private void checksumVerify(int actualChecksum, int expectedChecksum, String msg) {
        assert actualChecksum == expectedChecksum : msg;
    }

    private AggregationWindowSummary.WorkSpecificSummary parseWorkSpecificSummary(CheckedInputStream cin, WorkEntities.WorkType workType)
        throws IOException {
        Profile.TraceCtxDetailList traceDetails;
        switch (workType) {
            case cpu_sample_work:
                traceDetails = Deserializer.readCheckedDelimited(Profile.TraceCtxDetailList.parser(), cin, "traceDetails for cpu_sample_work");
                return new AggregationWindowSummary.CpuSampleSummary(traceDetails);
            case io_trace_work:
                traceDetails = Deserializer.readCheckedDelimited(Profile.TraceCtxDetailList.parser(), cin, "traceDetails for io_trace_work");
                return new AggregationWindowSummary.IOTraceSummary(traceDetails);
            default:
                throw new IllegalArgumentException("Unsupported work type for parsing work specific summary details from profile " + workType);
        }
    }

    private StacktraceTreeIterable parseStacktraceTree(InputStream in) throws IOException {
        // tree is serialized in DFS manner. First node being the root.
        int nodeCount = 1; // for root node
        int parsedNodeCount = 0;
        List<Profile.FrameNodeList> parsedFrameNodes = new ArrayList<>();
        do {
            Profile.FrameNodeList frameNodeList = Profile.FrameNodeList.parseDelimitedFrom(in);
            for(Profile.FrameNode node: frameNodeList.getFrameNodesList()) {
                nodeCount += node.getChildCount();
            }
            parsedNodeCount += frameNodeList.getFrameNodesCount();
            parsedFrameNodes.add(frameNodeList);
        } while(parsedNodeCount < nodeCount && parsedNodeCount > 0);

        return new StacktraceTreeIterable(parsedFrameNodes);
    }
}
