package fk.prof.backend.mock;

import com.google.common.collect.Sets;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class MockProfileObjects {
  public static Recording.RecordingHeader getRecordingHeader(long workId) {
    WorkEntities.WorkAssignment workAssignment = WorkEntities.WorkAssignment.newBuilder()
        .addWork(
            WorkEntities.Work.newBuilder()
                .setWType(WorkEntities.WorkType.cpu_sample_work)
                .setCpuSample(WorkEntities.CpuSampleWork.newBuilder()
                    .setFrequency(100)
                    .setMaxFrames(64)
                )
        )
        .setWorkId(workId)
        .setIssueTime(LocalDateTime.now().toString())
        .setDelay(180)
        .setDuration(60)
        .setDescription("Test Work")
        .build();
    Recording.RecordingHeader recordingHeader = Recording.RecordingHeader.newBuilder()
        .setRecorderVersion(1)
        .setControllerVersion(2)
        .setControllerId(3)
        .setWorkAssignment(workAssignment)
        .build();

    return recordingHeader;
  }

  public static Recording.RecordingHeader getRecordingHeader(long workId, int recorderVersion) {
    WorkEntities.WorkAssignment workAssignment = WorkEntities.WorkAssignment.newBuilder()
        .addWork(
            WorkEntities.Work.newBuilder()
                .setWType(WorkEntities.WorkType.cpu_sample_work)
                .setCpuSample(WorkEntities.CpuSampleWork.newBuilder()
                    .setFrequency(100)
                    .setMaxFrames(64)
                )
        )
        .setWorkId(workId)
        .setIssueTime(LocalDateTime.now().toString())
        .setDelay(180)
        .setDuration(60)
        .setDescription("Test Work")
        .build();
    Recording.RecordingHeader recordingHeader = Recording.RecordingHeader.newBuilder()
        .setRecorderVersion(recorderVersion)
        .setControllerVersion(2)
        .setControllerId(3)
        .setWorkAssignment(workAssignment)
        .build();

    return recordingHeader;
  }

  public static List<Recording.StackSample> getPredefinedStackSamples(int traceId) {
    Recording.StackSample stackSample1 = getMockStackSample(1, new char[]{'D', 'C', 'D', 'C', 'Y'});
    Recording.StackSample stackSample2 = getMockStackSample(1, new char[]{'D', 'C', 'E', 'D', 'C', 'Y'});
    Recording.StackSample stackSample3 = getMockStackSample(1, new char[]{'C', 'F', 'E', 'D', 'C', 'Y'});
    return Arrays.asList(stackSample1, stackSample2, stackSample3);
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

  public static Recording.RecordingChunk getMockChunkWithCpuWseAndStackSample(Recording.StackSampleWse currentStackSampleWse, Recording.StackSampleWse prevStackSampleWse) {
    Recording.RecordingChunk.Builder chunkBuilder = Recording.RecordingChunk.newBuilder();
    chunkBuilder.setIndexedData(
        Recording.IndexedData.newBuilder()
            .addAllMethodInfo(generateMethodIndex(currentStackSampleWse, prevStackSampleWse))
            .addAllTraceCtx(generateTraceIndex(currentStackSampleWse, prevStackSampleWse))
            .build())
        .addWse(Recording.Wse.newBuilder()
            .setWType(WorkEntities.WorkType.cpu_sample_work)
            .setCpuSampleEntry(currentStackSampleWse)
            .build());
    return chunkBuilder.build();
  }

  private static List<Recording.MethodInfo> generateMethodIndex(Recording.StackSampleWse currentStackSampleWse, Recording.StackSampleWse prevStackSampleWse) {
    Set<Long> currentMethodIds = uniqueMethodIdsInWse(currentStackSampleWse);
    Set<Long> prevMethodIds = uniqueMethodIdsInWse(prevStackSampleWse);
    Set<Long> newMethodIds = Sets.difference(currentMethodIds, prevMethodIds);
    return newMethodIds.stream()
        .map(mId -> Recording.MethodInfo.newBuilder()
            .setFileName("").setClassFqdn("").setSignature("()")
            .setMethodId(mId)
            .setMethodName(String.valueOf((char) mId.intValue()))
            .build())
        .collect(Collectors.toList());
  }

  private static List<Recording.TraceContext> generateTraceIndex(Recording.StackSampleWse currentStackSampleWse, Recording.StackSampleWse prevStackSampleWse) {
    Set<Integer> currentTraceIds = currentStackSampleWse.getStackSampleList().stream().flatMap(stackSample -> stackSample.getTraceIdList().stream()).collect(Collectors.toSet());
    Set<Integer> prevTraceIds = prevStackSampleWse == null
        ? new HashSet<>()
        : prevStackSampleWse.getStackSampleList().stream().flatMap(stackSample -> stackSample.getTraceIdList().stream()).collect(Collectors.toSet());
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
}
