package fk.prof.aggregation.model;

import fk.prof.aggregation.serialize.SerializationException;
import fk.prof.aggregation.serialize.Serializer;
import fk.prof.idl.Profile;
import fk.prof.idl.WorkEntities;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;
import java.util.zip.GZIPOutputStream;

/**
 * @author gaurav.ashok
 */
public class AggregationWindowSerializer implements Serializer {

    public static final int VERSION = 1;
    public static final int AGGREGATION_FILE_MAGIC_NUM = 0x19A9F5C2;
    public static final int STACKTRACETREE_SERIAL_BATCHSIZE = 1000;

    private WorkEntities.WorkType workType;
    private FinalizedAggregationWindow aggregation;

    public AggregationWindowSerializer(FinalizedAggregationWindow aggregation, WorkEntities.WorkType workType) {
        this.aggregation = aggregation;
        this.workType = workType;
    }

    @Override
    public void serialize(OutputStream out) throws IOException {
        Checksum checksum = new Adler32();
        CheckedOutputStream cout = new CheckedOutputStream(out, checksum);

        Serializer.writeVariantInt32(AGGREGATION_FILE_MAGIC_NUM, cout);

        // header
        Serializer.writeCheckedDelimited(aggregation.buildHeaderProto(VERSION, WorkEntities.WorkType.cpu_sample_work), cout);

        Profile.TraceCtxNames traceNames = aggregation.buildTraceCtxNamesProto(workType);
        Profile.TraceCtxDetailList traceDetails = aggregation.buildTraceCtxDetailListProto(workType, traceNames);

        // traces
        Serializer.writeCheckedDelimited(traceNames, cout);
        Serializer.writeCheckedDelimited(traceDetails, cout);

        // profiles summary
        checksum.reset();
        for(Profile.ProfileWorkInfo workInfo: aggregation.buildProfileWorkInfoProto(workType, traceNames)) {
            if(workInfo != null) {
                workInfo.writeDelimitedTo(cout);
            }
        }
        // end flag for profile summary
        Serializer.writeVariantInt32(0, cout);
        Serializer.writeVariantInt32((int)checksum.getValue(), cout);

        // work specific aggregated samples
        switch (workType) {
            case cpu_sample_work:
                new CpuSamplingAggregatedSamplesSerializer(aggregation.cpuSamplingAggregationBucket, traceNames).serialize(out);
        }
    }

    private static class CpuSamplingAggregatedSamplesSerializer implements Serializer {

        private FinalizedCpuSamplingAggregationBucket cpuSamplingAggregation;
        private Profile.TraceCtxNames traces;

        public CpuSamplingAggregatedSamplesSerializer(FinalizedCpuSamplingAggregationBucket cpuSamplingAggregation, Profile.TraceCtxNames traces) {
            this.cpuSamplingAggregation = cpuSamplingAggregation;
            this.traces = traces;
        }

        @Override
        public void serialize(OutputStream out) throws IOException {

            Checksum checksum = new Adler32();
            CheckedOutputStream cout = new CheckedOutputStream(out, checksum);

            // method lookup
            Serializer.writeCheckedDelimited(cpuSamplingAggregation.methodIdLookup.buildMethodIdLookupProto(), cout);

            // stacktrace tree
            checksum.reset();
            int index = 0;
            for(String traceName: traces.getNameList()) {
                FinalizedCpuSamplingAggregationBucket.NodeVisitor visitor =
                        new FinalizedCpuSamplingAggregationBucket.NodeVisitor(cout, STACKTRACETREE_SERIAL_BATCHSIZE, index);

                try {
                    cpuSamplingAggregation.traceDetailLookup.get(traceName).getGlobalRoot().traverse(visitor);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new SerializationException("Unexpected error while traversing stacktrace tree", e);
                }
                visitor.end();
                ++index;
            }
            Serializer.writeVariantInt32((int) checksum.getValue(), cout);
        }
    }
}
