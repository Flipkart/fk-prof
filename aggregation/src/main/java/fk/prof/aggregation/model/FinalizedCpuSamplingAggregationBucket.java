package fk.prof.aggregation.model;

import fk.prof.idl.Profile.*;
import fk.prof.aggregation.stacktrace.StacktraceFrameNode;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public class FinalizedCpuSamplingAggregationBucket implements FinalizedWorkSpecificAggregationBucket {
  protected final MethodIdLookup methodIdLookup;
  protected final Map<String, CpuSamplingTraceDetail> traceDetailLookup;

  public FinalizedCpuSamplingAggregationBucket(MethodIdLookup methodIdLookup, Map<String, CpuSamplingTraceDetail> traceDetailLookup) {
    this.methodIdLookup = methodIdLookup;
    this.traceDetailLookup = traceDetailLookup;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof FinalizedCpuSamplingAggregationBucket)) {
      return false;
    }

    FinalizedCpuSamplingAggregationBucket other = (FinalizedCpuSamplingAggregationBucket) o;
    return this.methodIdLookup.equals(other.methodIdLookup)
        && this.traceDetailLookup.equals(other.traceDetailLookup);
  }

  protected TraceCtxNames buildTraceNamesProto() {
    TraceCtxNames.Builder builder = TraceCtxNames.newBuilder();
    builder.addAllName(traceDetailLookup.keySet());
    return builder.build();
  }

  protected TraceCtxDetailList buildTraceCtxListProto(TraceCtxNames traces) {
    TraceCtxDetailList.Builder builder = TraceCtxDetailList.newBuilder();

    int index = 0;
    for(String trace: traces.getNameList()) {
      CpuSamplingTraceDetail traceDetail = traceDetailLookup.getOrDefault(trace, null);
      if(traceDetail != null) {
        builder.addTraceCtx(TraceCtxDetail.newBuilder().setTraceIdx(index).setSampleCount(traceDetail.getSampleCount()));
      }

      ++index;
    }

    return builder.build();
  }

  /**
   * Serializes the stacktrace tree in a dfs order. It serializes the tree in batches of fixed size, reusing the memory
   * allocated for temporary data structures in subsequent batches.
   */
  protected static class NodeVisitor implements StacktraceFrameNode.NodeVisitor<CpuSamplingFrameNode> {
    private OutputStream out;
    private int batchSize;
    private FrameNodeList.Builder builder = FrameNodeList.newBuilder();

    public NodeVisitor(OutputStream out, int batchSize, int traceCtxId) {
      this.out = out;
      this.batchSize = batchSize;
      this.builder.setTraceCtxIdx(traceCtxId);
    }

    @Override
    public void visit(CpuSamplingFrameNode node) throws IOException {
      if(builder.getFrameNodesCount() >= batchSize) {
        builder.build().writeDelimitedTo(out);

        // clear this batch of nodes
        builder.clearFrameNodes();
      }
      builder.addFrameNodes(node.buildFrameNodeProto());
    }

    protected void end() throws IOException {
      if(builder.getFrameNodesCount() > 0) {
        builder.build().writeDelimitedTo(out);
      }
    }
  }
}
