package fk.prof.backend.aggregator;

import com.codahale.metrics.Meter;
import fk.prof.aggregation.model.*;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.model.profile.RecordedProfileIndexes;
import fk.prof.idl.Recording;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class IOTracingAggregationBucket extends WorkSpecificAggregationBucket<FinalizedIOTracingAggregationBucket> {
  private final MethodIdLookup methodIdLookup = new MethodIdLookup();
  private final ConcurrentHashMap<String, IOTracingTraceDetail> traceDetailLookup = new ConcurrentHashMap<>();

  /**
   * Aggregates io traces in the bucket. Throws {@link AggregationFailure} if aggregation fails
   *
   * @param ioTraceWse
   */
  public void aggregate(Recording.IOTraceWse ioTraceWse, RecordedProfileIndexes indexes, Meter mtrAggrFailures)
      throws AggregationFailure {
    try {
      for (Recording.IOTrace ioTrace : ioTraceWse.getTracesList()) {
        Recording.StackSample stackSample = ioTrace.getStack();
        if(stackSample == null) {
          throw new AggregationFailure("IO trace aggregation for samples with missing stacktrace is not supported");
        }
        for (Integer traceId : stackSample.getTraceIdList()) {//TODO: this is not necessarily the best way of doing this from temporal locality PoV (may be we want a de-duped DS), think thru this -jj
          String trace = indexes.getTrace(traceId);
          if (trace == null) {
            throw new AggregationFailure("Unknown trace id encountered in stack sample, aborting aggregation of this profile");
          }
          IOTracingTraceDetail traceDetail = traceDetailLookup.computeIfAbsent(trace,
              key -> new IOTracingTraceDetail()
          );

          List<Recording.Frame> frames = stackSample.getFrameList();
          if (frames.size() > 0) {
            // global sample count increment
            IOTracingFrameNode currentNode = stackSample.getSnipped() ? traceDetail.getUnclassifiableRoot() : traceDetail.getGlobalRoot();
            traceDetail.incrementSamples();

            //callee -> caller ordering in frames, so iterating bottom up in the list to merge in existing tree in root->leaf fashion
            for (int i = frames.size() - 1; i >= 0; i--) {
              Recording.Frame frame = frames.get(i);
              String method = indexes.getMethod(frame.getMethodId());
              if (method == null) {
                throw new AggregationFailure("Unknown method id encountered in stack sample, aborting aggregation of this profile");
              }
              int methodId = methodIdLookup.getOrAdd(method);
              currentNode = currentNode.getOrAddChild(methodId, frame.getLineNo());
              //The first frame is the on-cpu frame so incrementing on-cpu samples count
              if (i == 0) {
                int bytes = ioTrace.hasRead() ? ioTrace.getRead().getCount() : ioTrace.getWrite().getCount();
                boolean timeout = ioTrace.hasRead() && ioTrace.getRead().getTimeout();
                currentNode.addTrace(ioTrace.getFdId(), ioTrace.getType(), ioTrace.getLatencyNs(), bytes, timeout);
              }
            }
          }
        }
      }
    } catch (Exception ex) {
      mtrAggrFailures.mark();
      throw ex;
    }
  }

  @Override
  protected FinalizedIOTracingAggregationBucket buildFinalizedEntity() {
    return new FinalizedIOTracingAggregationBucket(
        methodIdLookup,
        traceDetailLookup
    );
  }
}
