package fk.prof.aggregation.model;

import fk.prof.idl.Profile;
import fk.prof.idl.Recording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class IOTracingProps {
  public final static int MAX_SAMPLES = 1500;

  private final int fdIdx;
  private final Recording.IOTraceType traceType;

  private AtomicInteger samples = new AtomicInteger(0);
  private List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
  private AtomicLong latencySum = new AtomicLong(0);
  private AtomicLong bytesSum = new AtomicLong(0);
  private AtomicInteger timeouts = new AtomicInteger(0);

  public IOTracingProps(int fdIdx, Recording.IOTraceType traceType) {
    this.fdIdx = fdIdx;
    this.traceType = traceType;
  }

  /**
   * Returns true if latency was recorded in internal buffer and percentile calculations will be accurate
   * Returns false if internal buffer is full and latency is dropped. In this case percentile calculations cannot be relied upon
   * Mean latency, timeouts, total bytes transferred calculations are not affected regardless
   * @param latency
   * @param bytes
   * @param timeout
   * @return
   */
  public boolean addSample(long latency, int bytes, boolean timeout) {
    latencySum.addAndGet(latency);
    if(bytes > 0) {
      bytesSum.addAndGet(bytes);
    }
    if(timeout) {
      timeouts.incrementAndGet();
    }

    if(samples.incrementAndGet() > MAX_SAMPLES) {
      return false;
    }
    latencies.add(latency);
    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof IOTracingProps)) {
      return false;
    }

    IOTracingProps other = (IOTracingProps) o;
    return this.fdIdx == other.fdIdx
        && this.traceType.equals(other.traceType)
        && this.samples.get() == other.samples.get();
  }

  protected Profile.IOTracingNodeProps buildProto() {
    Collections.sort(latencies);
    return Profile.IOTracingNodeProps.newBuilder()
        .setFdIdx(fdIdx)
        .setTraceType(traceType)
        .setSamples(samples.get())
        .setDropped(samples.get() > MAX_SAMPLES)
        .setBytes(bytesSum.get())
        .setMean((float)latencySum.get() / samples.get())
        .setLatency95((float)quantile(0.95, latencies))
        .setLatency99((float)quantile(0.99, latencies))
        .build();
  }

  protected static double quantile(final double q, List<Long> values) {
    if ((q > 1) || (q <= 0)) {
      throw new IllegalArgumentException("Invalid quantile: " + q);
    }
    int length = values.size();
    if (length == 0) {
      return Double.NaN;
    }
    if (length == 1) {
      return values.get(0);
    }

    double pos = q * (length + 1);
    double fpos = Math.floor(pos);
    int intPos = (int) fpos;
    double dif = pos - fpos;

    if (pos < 1) {
      return values.get(0);
    }
    if (pos >= (double)length) {
      return values.get(length - 1);
    }
    double lower = values.get(intPos - 1);
    double upper = values.get(intPos);
    return lower + dif * (upper - lower);
  }
}
