package fk.prof.aggregation.model;

import fk.prof.idl.Profile;
import fk.prof.idl.Recording;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class IOTracingFrameNodeTest {
  @Test
  public void testProtoModelWithAggregatedValues() {
    IOTracingFrameNode n = new IOTracingFrameNode(1, 10);
    n.addTrace(1, Recording.IOTraceType.file_read, 10, 100, true);
    n.addTrace(1, Recording.IOTraceType.file_read, 20, 200, true);
    n.addTrace(1, Recording.IOTraceType.file_read, 30, 300, false);
    n.addTrace(1, Recording.IOTraceType.file_write, 10, 0, false);
    n.addTrace(1, Recording.IOTraceType.file_write, 10, 100, false);
    n.addTrace(1, Recording.IOTraceType.file_write, 20, 200, false);
    n.addTrace(2, Recording.IOTraceType.file_write, 20, 200, false);

    n.getOrAddChild(2, 20);

    Profile.FrameNode fn = n.buildFrameNodeProto();
    Assert.assertEquals(1, fn.getMethodId());
    Assert.assertEquals(10, fn.getLineNo());
    Assert.assertTrue(fn.getChildCount() == 1);
    Assert.assertFalse(fn.hasCpuSamplingProps());
    Assert.assertEquals(3, fn.getIoTracingPropsCount());

    Set<Profile.IOTracingNodeProps> props = new HashSet<>();
    props.add(Profile.IOTracingNodeProps.newBuilder()
        .setFdIdx(1).setTraceType(Recording.IOTraceType.file_read)
        .setSamples(3).setLatency95(30).setLatency99(30)
        .setMean(20).setBytes(600).setDropped(false).build());
    props.add(Profile.IOTracingNodeProps.newBuilder()
        .setFdIdx(1).setTraceType(Recording.IOTraceType.file_write)
        .setSamples(3).setLatency95(20).setLatency99(20)
        .setMean((40.0f/3.0f)).setBytes(300).setDropped(false).build());
    props.add(Profile.IOTracingNodeProps.newBuilder()
        .setFdIdx(2).setTraceType(Recording.IOTraceType.file_write)
        .setSamples(1).setLatency95(20).setLatency99(20)
        .setMean(20).setBytes(200).setDropped(false).build());
    Assert.assertEquals(props, new HashSet<>(fn.getIoTracingPropsList()));
  }
}
