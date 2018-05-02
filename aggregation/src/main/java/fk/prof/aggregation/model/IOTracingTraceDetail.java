package fk.prof.aggregation.model;

import java.util.concurrent.atomic.AtomicInteger;

public class IOTracingTraceDetail {
  private final AtomicInteger sampleCount = new AtomicInteger(0);
  private final IOTracingFrameNode globalRoot;
  private final IOTracingFrameNode unclassifiableRoot;

  public IOTracingTraceDetail() {
    this.globalRoot = new IOTracingFrameNode(MethodIdLookup.GLOBAL_ROOT_METHOD_ID, MethodIdLookup.DEFAULT_LINE_NUMBER);
    this.unclassifiableRoot = this.globalRoot.getOrAddChild(MethodIdLookup.UNCLASSIFIABLE_ROOT_METHOD_ID, MethodIdLookup.DEFAULT_LINE_NUMBER);
  }

  public IOTracingFrameNode getGlobalRoot() {
    return this.globalRoot;
  }

  public IOTracingFrameNode getUnclassifiableRoot() {
    return this.unclassifiableRoot;
  }

  public void incrementSamples() {
    this.sampleCount.incrementAndGet();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof IOTracingTraceDetail)) {
      return false;
    }

    IOTracingTraceDetail other = (IOTracingTraceDetail) o;
    return this.sampleCount.get() == other.sampleCount.get()
        && this.globalRoot.equals(other.globalRoot);
  }

  protected int getSampleCount() {
    return sampleCount.get();
  }
}
