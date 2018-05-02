package fk.prof.backend.aggregator;

import com.codahale.metrics.Meter;
import fk.prof.aggregation.model.*;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.model.profile.RecordedProfileIndexes;
import fk.prof.idl.Recording;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class CpuSamplingAggregationBucket extends WorkSpecificAggregationBucket<FinalizedCpuSamplingAggregationBucket> {
  private final MethodIdLookup methodIdLookup = new MethodIdLookup();
  private final ConcurrentHashMap<String, CpuSamplingTraceDetail> traceDetailLookup = new ConcurrentHashMap<>();

  /**
   * Aggregates stack samples in the bucket. Throws {@link AggregationFailure} if aggregation fails
   *
   * @param stackSampleWse
   */
  public void aggregate(Recording.StackSampleWse stackSampleWse, RecordedProfileIndexes indexes, Meter mtrAggrFailures)
      throws AggregationFailure {
    try {
      for (Recording.StackSample stackSample : stackSampleWse.getStackSampleList()) {
        for (Integer traceId : stackSample.getTraceIdList()) {//TODO: this is not necessarily the best way of doing this from temporal locality PoV (may be we want a de-duped DS), think thru this -jj
          String trace = indexes.getTrace(traceId);
          if (trace == null) {
            throw new AggregationFailure("Unknown trace id encountered in stack sample, aborting aggregation of this profile");
          }
          CpuSamplingTraceDetail traceDetail = traceDetailLookup.computeIfAbsent(trace,
              key -> new CpuSamplingTraceDetail()
          );

          List<Recording.Frame> frames = stackSample.getFrameList();
          if (frames.size() > 0) {

            // global sample count increment
            CpuSamplingFrameNode currentNode = traceDetail.getGlobalRoot();
            currentNode.incrementOnStackSamples();
            traceDetail.incrementSamples();

            if(stackSample.getSnipped()) {
              currentNode = traceDetail.getUnclassifiableRoot();
              currentNode.incrementOnStackSamples();
            }

            //callee -> caller ordering in frames, so iterating bottom up in the list to merge in existing tree in root->leaf fashion
            for (int i = frames.size() - 1; i >= 0; i--) {
              Recording.Frame frame = frames.get(i);
              String method = indexes.getMethod(frame.getMethodId());
              if (method == null) {
                throw new AggregationFailure("Unknown method id encountered in stack sample, aborting aggregation of this profile");
              }
              int methodId = methodIdLookup.getOrAdd(method);
              currentNode = currentNode.getOrAddChild(methodId, frame.getLineNo());
              currentNode.incrementOnStackSamples();
              //The first frame is the on-cpu frame so incrementing on-cpu samples count
              if (i == 0) {
                currentNode.incrementOnCpuSamples();
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
  protected FinalizedCpuSamplingAggregationBucket buildFinalizedEntity() {
    return new FinalizedCpuSamplingAggregationBucket(
        methodIdLookup,
        traceDetailLookup
    );
  }
}
