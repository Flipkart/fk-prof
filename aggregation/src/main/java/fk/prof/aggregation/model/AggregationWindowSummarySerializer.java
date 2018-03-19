package fk.prof.aggregation.model;

import fk.prof.aggregation.serialize.Serializer;
import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

/**
 * @author gaurav.ashok
 */
public class AggregationWindowSummarySerializer implements Serializer {

    public static final int VERSION = 1;
    public static final int SUMMARY_FILE_MAGIC_NUM = 0x68262fed;

    FinalizedAggregationWindow aggregation;

    public AggregationWindowSummarySerializer(FinalizedAggregationWindow aggregation) {
        this.aggregation = aggregation;
    }

    @Override
    public void serialize(OutputStream out) throws IOException {
        Checksum checksum = new Adler32();
        CheckedOutputStream cout = new CheckedOutputStream(out, checksum);

        Serializer.writeVariantInt32(SUMMARY_FILE_MAGIC_NUM, cout);

        // header
        Serializer.writeCheckedDelimited(aggregation.buildHeaderProto(VERSION), cout);

        // all traces
        Profile.TraceCtxNames traceNames = aggregation.buildTraceCtxNamesProto();

        Serializer.writeCheckedDelimited(traceNames, cout);

        // all profile work summary
        checksum.reset();
        for(Profile.ProfileWorkInfo workInfo: aggregation.buildProfileWorkInfoProto(traceNames)) {
            if(workInfo != null) {
                workInfo.writeDelimitedTo(cout);
            }
        }
        // end flag for profile work summary
        Serializer.writeVariantInt32(0, cout);
        Serializer.writeVariantInt32((int)checksum.getValue(), cout);

        // work specific trace summary
        Serializer.writeCheckedDelimited(aggregation.buildTraceCtxDetailListProto(WorkEntities.WorkType.cpu_sample_work, traceNames), cout);
        Serializer.writeCheckedDelimited(aggregation.buildTraceCtxDetailListProto(WorkEntities.WorkType.io_trace_work, traceNames), cout);
    }
}
