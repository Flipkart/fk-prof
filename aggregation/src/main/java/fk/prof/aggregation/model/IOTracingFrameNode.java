package fk.prof.aggregation.model;

import fk.prof.aggregation.stacktrace.StacktraceFrameNode;
import fk.prof.idl.Profile;
import fk.prof.idl.Recording;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class IOTracingFrameNode extends StacktraceFrameNode<IOTracingFrameNode> {
  private final int methodId;
  private final int lineNumber;
  private final List<IOTracingFrameNode> children = new ArrayList<>();

  private Map<Recording.IOTraceType, Map<Integer, IOTracingProps>> props;

  public IOTracingFrameNode(int methodId, int lineNumber) {
    this.methodId = methodId;
    this.lineNumber = lineNumber;
  }

  public IOTracingFrameNode getOrAddChild(int childMethodId, int childLineNumber) {
    synchronized (children) {
      IOTracingFrameNode result = null;
      Iterator<IOTracingFrameNode> i = children.iterator();
      // Since count of children is going to be small for a node (in scale of tens usually),
      // sticking with arraylist impl of children with O(N) traversal
      while (i.hasNext()) {
        IOTracingFrameNode child = i.next();
        if (child.methodId == childMethodId && child.lineNumber == childLineNumber) {
          result = child;
          break;
        }
      }

      if (result == null) {
        result = new IOTracingFrameNode(childMethodId, childLineNumber);
        children.add(result);
      }

      return result;
    }
  }

  public boolean addTrace(int fdIdx, Recording.IOTraceType traceType, long latency, int bytes, boolean timeout) {
    return getTracesForFd(fdIdx, traceType).addSample(latency, bytes, timeout);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof IOTracingFrameNode)) {
      return false;
    }

    IOTracingFrameNode other = (IOTracingFrameNode) o;
    return this.methodId == other.methodId
        && this.lineNumber == other.lineNumber
        && this.children.size() == other.children.size()
        && this.children.containsAll(other.children)
        && other.children.containsAll(this.children);
  }

  @Override
  public int hashCode() {
    final int PRIME = 31;
    int result = 1;
    result = result * PRIME + methodId;
    result = result * PRIME + lineNumber;
    return result;
  }

  protected Profile.FrameNode buildFrameNodeProto() {
    List<Profile.IOTracingNodeProps> nodeProps = this.props.values().stream()
        .flatMap(fdEntry -> fdEntry.values().stream())
        .map(IOTracingProps::buildProto)
        .collect(Collectors.toList());

    return Profile.FrameNode.newBuilder()
      .setMethodId(methodId)
      .setChildCount(children.size())
      .setLineNo(lineNumber)
      .addAllIoTracingProps(nodeProps)
      .build();
  }

  @Override
  protected Iterable<IOTracingFrameNode> children() {
    return children;
  }

  private Map<Recording.IOTraceType, Map<Integer, IOTracingProps>> getProps() {
    if(props == null) {
      props = new ConcurrentHashMap<>(Recording.IOTraceType.values().length * 2);
    }
    return props;
  }

  private Map<Integer, IOTracingProps> getPropsForActivityType(Recording.IOTraceType type) {
    Map<Recording.IOTraceType, Map<Integer, IOTracingProps>> props = getProps();
    Map<Integer, IOTracingProps> propsForType = props.get(type);
    if(propsForType == null) {
      propsForType = new ConcurrentHashMap<>();
      props.put(type, propsForType);
    }
    return propsForType;
  }

  private IOTracingProps getTracesForFd(int fd, Recording.IOTraceType type) {
    Map<Integer, IOTracingProps> propsForType = getPropsForActivityType(type);
    IOTracingProps traces = propsForType.get(fd);
    if(traces == null) {
      traces = new IOTracingProps(fd, type);
      propsForType.put(fd, traces);
    }
    return traces;
  }
}
