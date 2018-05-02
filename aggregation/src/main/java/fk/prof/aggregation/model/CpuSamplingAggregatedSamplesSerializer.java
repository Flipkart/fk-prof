package fk.prof.aggregation.model;

import fk.prof.aggregation.serialize.SerializationException;
import fk.prof.aggregation.serialize.Serializer;
import fk.prof.idl.Profile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

class CpuSamplingAggregatedSamplesSerializer implements Serializer {

  private final int batchSize;
  private FinalizedCpuSamplingAggregationBucket cpuSamplingAggregation;
  private Profile.TraceCtxNames traces;

  public CpuSamplingAggregatedSamplesSerializer(int batchSize, FinalizedCpuSamplingAggregationBucket cpuSamplingAggregation, Profile.TraceCtxNames traces) {
    this.cpuSamplingAggregation = cpuSamplingAggregation;
    this.traces = traces;
    this.batchSize = batchSize;
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
          new FinalizedCpuSamplingAggregationBucket.NodeVisitor(cout, batchSize, index);

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
