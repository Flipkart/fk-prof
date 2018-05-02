package fk.prof.aggregation.model;

import fk.prof.aggregation.serialize.SerializationException;
import fk.prof.aggregation.serialize.Serializer;
import fk.prof.idl.Profile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

class IOTracingAggregatedSamplesSerializer implements Serializer {

  private final int batchSize;
  private FinalizedIOTracingAggregationBucket ioTracingAggregation;
  private Profile.TraceCtxNames traces;

  public IOTracingAggregatedSamplesSerializer(int batchSize, FinalizedIOTracingAggregationBucket ioTracingAggregation, Profile.TraceCtxNames traces) {
    this.ioTracingAggregation = ioTracingAggregation;
    this.traces = traces;
    this.batchSize = batchSize;
  }

  @Override
  public void serialize(OutputStream out) throws IOException {

    Checksum checksum = new Adler32();
    CheckedOutputStream cout = new CheckedOutputStream(out, checksum);

    // method lookup
    Serializer.writeCheckedDelimited(ioTracingAggregation.methodIdLookup.buildMethodIdLookupProto(), cout);

    // stacktrace tree
    checksum.reset();
    int index = 0;
    for(String traceName: traces.getNameList()) {
      FinalizedIOTracingAggregationBucket.NodeVisitor visitor =
          new FinalizedIOTracingAggregationBucket.NodeVisitor(cout, batchSize, index);

      try {
        ioTracingAggregation.traceDetailLookup.get(traceName).getGlobalRoot().traverse(visitor);
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
