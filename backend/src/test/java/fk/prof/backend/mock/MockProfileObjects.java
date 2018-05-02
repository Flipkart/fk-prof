package fk.prof.backend.mock;

import com.google.common.collect.Sets;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;
import fk.prof.idl.WorkEntities.WorkType;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static fk.prof.idl.WorkEntities.WorkType.*;

public class MockProfileObjects {

  public static Set<WorkType> wType_cpu = new HashSet<>(Arrays.asList(cpu_sample_work));
  public static Set<WorkType> wType_all = new HashSet<>(Arrays.asList(cpu_sample_work, io_trace_work));

  public static Recording.RecordingHeader getRecordingHeader(long workId) {
    return getRecordingHeader(workId, wType_cpu);
  }

  public static Recording.RecordingHeader getRecordingHeader(long workId, Set<WorkType> workTypes) {
    return getRecordingHeader(workId, 1, workTypes);
  }

  public static Recording.RecordingHeader getRecordingHeader(long workId, int recorderVersion) {
    return getRecordingHeader(workId, recorderVersion, wType_cpu);
  }

  public static Recording.RecordingHeader getRecordingHeader(long workId, int recorderVersion, Set<WorkType> workTypes) {
    WorkEntities.WorkAssignment.Builder workAssignmentBuilder = WorkEntities.WorkAssignment.newBuilder()
        .setWorkId(workId)
        .setIssueTime(LocalDateTime.now().toString())
        .setDelay(180)
        .setDuration(60)
        .setDescription("Test Work");

    for(WorkType wt: workTypes) {
      switch (wt) {
        case cpu_sample_work:
          workAssignmentBuilder.addWork(WorkEntities.Work.newBuilder()
                  .setWType(cpu_sample_work)
                  .setCpuSample(WorkEntities.CpuSampleWork.newBuilder()
                          .setFrequency(100)
                          .setMaxFrames(64)
                  )
          );
          break;
        case io_trace_work:
          workAssignmentBuilder.addWork(WorkEntities.Work.newBuilder()
                  .setWType(io_trace_work)
                  .setIoTrace(WorkEntities.IOTraceWork.newBuilder()
                          .setLatencyThresholdMs(100)
                          .setMaxFrames(64)
                  )
          );
          break;
        default:
          throw new IllegalArgumentException("suppror for wType: " + wt.name() + "not added");
      }
    }

    Recording.RecordingHeader recordingHeader = Recording.RecordingHeader.newBuilder()
        .setRecorderVersion(recorderVersion)
        .setControllerVersion(2)
        .setControllerId(3)
        .setWorkAssignment(workAssignmentBuilder.build())
        .build();

    return recordingHeader;
  }

  public static List<Recording.StackSample> getPredefinedStackSamples(int traceId) {
    Recording.StackSample stackSample1 = getMockStackSample(traceId, new char[]{'D', 'C', 'D', 'C', 'Y'});
    Recording.StackSample stackSample2 = getMockStackSample(traceId, new char[]{'D', 'C', 'E', 'D', 'C', 'Y'});
    Recording.StackSample stackSample3 = getMockStackSample(traceId, new char[]{'C', 'F', 'E', 'D', 'C', 'Y'});
    return Arrays.asList(stackSample1, stackSample2, stackSample3);
  }

  public static List<Recording.IOTrace> getPredefinedIOTraces(int traceId) {
    return Arrays.asList(
        buildIOTraceProto(10, 1000, true, getMockStackSample(traceId, new char[]{'A', 'B', 'D'})),
        buildIOTraceProto(11, 1100, true, getMockStackSample(traceId, new char[]{'A', 'B', 'E', 'F'})),
        buildIOTraceProto(10, 1000, false, getMockStackSample(traceId, new char[]{'A', 'B', 'E'})),
        buildIOTraceProto(11, 1100, false, getMockStackSample(traceId, new char[]{'A', 'B', 'E', 'E'}))
    );
  }

  public static Recording.IOTrace buildIOTraceProto(int fd, int count, boolean read, Recording.StackSample ss) {
    Recording.IOTrace.Builder builder = Recording.IOTrace.newBuilder().setFdId(fd).setLatencyNs(1000).setTs(1000)
        .setStack(ss);
    if(read) {
      builder
          .setType(fd % 2 == 0 ? Recording.IOTraceType.file_read : Recording.IOTraceType.socket_read)
          .setRead(Recording.FdRead.newBuilder().setCount(count).setTimeout(false).build());
    }
    else {
      builder
          .setType(fd % 2 == 0 ? Recording.IOTraceType.file_write : Recording.IOTraceType.socket_write)
          .setWrite(Recording.FdWrite.newBuilder().setCount(count).build());
    }
    return builder.build();
  }

  //TODO: Keeping the logic around in case we want to generate random samples in high volume later
  public static List<Recording.StackSample> getRandomStackSamples(int traceId) {
    List<Recording.StackSample> baseline = getPredefinedStackSamples(traceId);
    List<Recording.StackSample> samples = new ArrayList<>();
    Random random = new Random();
    int samplesCount = baseline.size();
    int baselineSampleIndex = 0;
    while (samples.size() < samplesCount) {
      Recording.StackSample baselineSample = baseline.get(baselineSampleIndex);
      Recording.StackSample.Builder sampleBuilder = Recording.StackSample.newBuilder()
          .setStartOffsetMicros(1000).setThreadId(1).addTraceId(traceId);

      List<Long> methodIds = new ArrayList(baselineSample.getFrameList().stream().map(frame -> frame.getMethodId()).collect(Collectors.toSet()));
      List<Recording.Frame> frames = new ArrayList<>();
      for (int i = 0; i < baselineSample.getFrameCount(); i++) {
        Recording.Frame baselineFrame = baselineSample.getFrame(i);
        long methodId = baselineFrame.getMethodId();
        if (random.nextInt(4 + (i * 2)) == 0) {
          methodId = methodIds.get(random.nextInt(methodIds.size()));
        }
        Recording.Frame frame = Recording.Frame.newBuilder().setBci(1).setLineNo(10).setMethodId(methodId).build();
        frames.add(frame);
      }
      Recording.StackSample sample = sampleBuilder.addAllFrame(frames).build();
      samples.add(sample);

      baselineSampleIndex++;
      if (baselineSampleIndex == baseline.size()) {
        baselineSampleIndex = 0;
      }
    }

    return samples;
  }

  public static List<Recording.RecordingChunk> getMockRecordingChunks(Map<WorkType, List> wses) {
    boolean allSamplesProcessed = false;
    int iteration = 0;
    List<Recording.RecordingChunk> chunks = new ArrayList<>();

    List<Recording.MethodInfo> prevMethodInfo = new ArrayList<>();
    List<Recording.TraceContext> prevTraceCtx = new ArrayList<>();
    List<Recording.FDInfo> prevFdInfo = new ArrayList<>();

    while(true) {
      allSamplesProcessed = true;
      Recording.RecordingChunk.Builder builder = Recording.RecordingChunk.newBuilder();

      for(WorkType wt: wses.keySet()) {
        if(wses.get(wt).size() > iteration) {
          switch (wt) {
            case cpu_sample_work:
              appendCpuWse(builder, (Recording.StackSampleWse) wses.get(wt).get(iteration), prevMethodInfo, prevTraceCtx);
              break;
            case io_trace_work:
              appendIoTraceWse(builder, (Recording.IOTraceWse) wses.get(wt).get(iteration), prevMethodInfo, prevTraceCtx,
                  prevFdInfo);
              break;
            default:
                throw new IllegalStateException("support for wType: " + wt.name() + "not added");
          }
          allSamplesProcessed = false;
        }
      }

      if(allSamplesProcessed)
        break;

      chunks.add(builder.build());
      iteration++;
    }
    return chunks;
  }

  static void appendCpuWse(Recording.RecordingChunk.Builder builder, Recording.StackSampleWse wse,
                                  List<Recording.MethodInfo> prevMethodInfo,
                                  List<Recording.TraceContext> prevTraceCtx) {
    List<Recording.MethodInfo> newMethodInfo = generateMethodIndex(wse, prevMethodInfo);
    List<Recording.TraceContext> newTraceCtx = generateTraceIndex(wse, prevTraceCtx);

    builder.getIndexedDataBuilder().addAllMethodInfo(newMethodInfo).addAllTraceCtx(newTraceCtx);
    builder.addWse(Recording.Wse.newBuilder()
        .setWType(cpu_sample_work)
        .setCpuSampleEntry(wse)
        .build());

    // update prev indices
    prevMethodInfo.addAll(newMethodInfo);
    prevTraceCtx.addAll(newTraceCtx);
  }

  static void appendIoTraceWse(Recording.RecordingChunk.Builder builder, Recording.IOTraceWse wse,
                               List<Recording.MethodInfo> prevMethodInfo,
                               List<Recording.TraceContext> prevTraceCtx,
                               List<Recording.FDInfo> prevFdInfo) {
    List<Recording.MethodInfo> newMethodInfo = generateMethodIndex(wse, prevMethodInfo);
    List<Recording.TraceContext> newTraceCtx = generateTraceIndex(wse, prevTraceCtx);
    List<Recording.FDInfo> newFds = generateFDIndex(wse, prevFdInfo);

    builder.getIndexedDataBuilder().addAllMethodInfo(newMethodInfo).addAllTraceCtx(newTraceCtx)
        .addAllFdInfo(newFds);
    builder.addWse(Recording.Wse.newBuilder()
        .setWType(io_trace_work)
        .setIoTraceEntry(wse)
        .build());

    // update prev indices
    prevMethodInfo.addAll(newMethodInfo);
    prevTraceCtx.addAll(newTraceCtx);
    prevFdInfo.addAll(newFds);
  }

  private static List<Recording.MethodInfo> generateMethodIndex(Recording.StackSampleWse currentStackSampleWse,
                                                                List<Recording.MethodInfo> prevMethodInfo) {
    Set<Long> currentMethodIds = uniqueMethodIdsInWse(currentStackSampleWse);
    Set<Long> prevMethodIds = prevMethodInfo.stream().map(Recording.MethodInfo::getMethodId).collect(Collectors.toSet());
    Set<Long> newMethodIds = Sets.difference(currentMethodIds, prevMethodIds);
    return newMethodIds.stream()
        .map(mId -> Recording.MethodInfo.newBuilder()
            .setFileName("").setClassFqdn("").setSignature("()")
            .setMethodId(mId)
            .setMethodName(String.valueOf((char) mId.intValue()))
            .build())
        .collect(Collectors.toList());
  }

  private static List<Recording.MethodInfo> generateMethodIndex(Recording.IOTraceWse currentIOTraceWse,
                                                                List<Recording.MethodInfo> prevMethodInfo) {
    Set<Long> currentMethodIds = uniqueMethodIdsInWse(currentIOTraceWse);
    Set<Long> prevMethodIds = prevMethodInfo.stream().map(Recording.MethodInfo::getMethodId).collect(Collectors.toSet());
    Set<Long> newMethodIds = Sets.difference(currentMethodIds, prevMethodIds);
    return newMethodIds.stream()
        .map(mId -> Recording.MethodInfo.newBuilder()
            .setFileName("").setClassFqdn("").setSignature("()")
            .setMethodId(mId)
            .setMethodName(String.valueOf((char) mId.intValue()))
            .build())
        .collect(Collectors.toList());
  }

  private static List<Recording.TraceContext> generateTraceIndex(Recording.StackSampleWse currentStackSampleWse,
                                                                 List<Recording.TraceContext> prevTraceCtx) {
    Set<Integer> currentTraceIds = currentStackSampleWse.getStackSampleList().stream().flatMap(stackSample -> stackSample.getTraceIdList().stream()).collect(Collectors.toSet());
    Set<Integer> prevTraceIds = prevTraceCtx == null
        ? new HashSet<>()
        : prevTraceCtx.stream().map(Recording.TraceContext::getTraceId).collect(Collectors.toSet());
    Set<Integer> newTraceIds = Sets.difference(currentTraceIds, prevTraceIds);
    return newTraceIds.stream()
        .map(tId -> Recording.TraceContext.newBuilder()
            .setCoveragePct(5)
            .setTraceId(tId)
            .setTraceName(String.valueOf(tId))
            .setIsGenerated(false)
            .build())
        .collect(Collectors.toList());
  }

  private static List<Recording.TraceContext> generateTraceIndex(Recording.IOTraceWse currentIOTraceWse,
                                                                 List<Recording.TraceContext> prevTraceCtx) {
    Set<Integer> currentTraceIds = currentIOTraceWse.getTracesList().stream().flatMap(ioTrace -> ioTrace.getStack().getTraceIdList().stream()).collect(Collectors.toSet());
    Set<Integer> prevTraceIds = prevTraceCtx == null
        ? new HashSet<>()
        : prevTraceCtx.stream().map(Recording.TraceContext::getTraceId).collect(Collectors.toSet());
    Set<Integer> newTraceIds = Sets.difference(currentTraceIds, prevTraceIds);
    return newTraceIds.stream()
        .map(tId -> Recording.TraceContext.newBuilder()
            .setCoveragePct(5)
            .setTraceId(tId)
            .setTraceName(String.valueOf(tId))
            .setIsGenerated(false)
            .build())
        .collect(Collectors.toList());
  }

  private static List<Recording.FDInfo> generateFDIndex(Recording.IOTraceWse currentIOTraceWse,
                                                        List<Recording.FDInfo> prevFdInfo) {
    Set<Integer> currentFDIds = currentIOTraceWse.getTracesList().stream().map(Recording.IOTrace::getFdId)
        .collect(Collectors.toSet());

    Set<Integer> prevFDIds = prevFdInfo == null
        ? new HashSet<>()
        : prevFdInfo.stream().map(Recording.FDInfo::getId).collect(Collectors.toSet());

    Set<Integer> newFDIds = Sets.difference(currentFDIds, prevFDIds);

    return newFDIds.stream()
        .map(fId -> {
          Recording.FDInfo.Builder builder = Recording.FDInfo.newBuilder().setId(fId);
          if(fId % 2 == 0) {
            builder
                .setFileInfo(Recording.FileInfo.newBuilder().setFilename("/hello_world.txt").setFlags(""))
                .setFdType(Recording.FDType.file);
          }
          else {
            builder
                .setSocketInfo(Recording.SocketInfo.newBuilder().setAddress("http://hello.world").setConnect(true))
                .setFdType(Recording.FDType.socket);
          }
          return builder.build();
        })
        .collect(Collectors.toList());
  }

  //dummyMethods is a char array. each method name is just a single character. method id is character's numeric repr
  //returned frames are in the same order as method names in input array
  private static List<Recording.Frame> getMockFrames(char[] dummyMethods) {
    List<Recording.Frame> frames = new ArrayList<>();
    for (char dummyMethod : dummyMethods) {
      frames.add(Recording.Frame.newBuilder()
          .setMethodId((int) (dummyMethod))
          .setBci(1).setLineNo(10)
          .build());
    }
    return frames;
  }

  private static Recording.StackSample getMockStackSample(int traceId, char[] dummyMethods) {
    return Recording.StackSample.newBuilder()
        .setStartOffsetMicros(1000).setThreadId(1).setSnipped(true)
        .addTraceId(traceId)
        .addAllFrame(getMockFrames(dummyMethods))
        .build();
  }

  private static Set<Long> uniqueMethodIdsInWse(Recording.StackSampleWse stackSampleWse) {
    if (stackSampleWse == null) {
      return new HashSet<>();
    }
    return stackSampleWse.getStackSampleList().stream()
        .flatMap(stackSample -> stackSample.getFrameList().stream())
        .map(frame -> frame.getMethodId())
        .collect(Collectors.toSet());
  }

  private static Set<Long> uniqueMethodIdsInWse(Recording.IOTraceWse ioTraceWse) {
    if (ioTraceWse == null) {
      return new HashSet<>();
    }
    return ioTraceWse.getTracesList().stream()
        .flatMap(ioTrace -> ioTrace.getStack().getFrameList().stream())
        .map(frame -> frame.getMethodId())
        .collect(Collectors.toSet());
  }
}
