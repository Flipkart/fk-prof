package fk.prof.backend.mock;

import com.google.common.collect.Sets;
import recording.Recorder;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class MockProfileObjects {
  public static Recorder.RecordingHeader getRecordingHeader(long workId) {
    Recorder.WorkAssignment workAssignment = Recorder.WorkAssignment.newBuilder()
        .addWork(
            Recorder.Work.newBuilder()
                .setWType(Recorder.WorkType.cpu_sample_work)
                .setCpuSample(Recorder.CpuSampleWork.newBuilder()
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
    Recorder.RecordingHeader recordingHeader = Recorder.RecordingHeader.newBuilder()
        .setRecorderVersion(1)
        .setControllerVersion(2)
        .setControllerId(3)
        .setWorkAssignment(workAssignment)
        .build();

    return recordingHeader;
  }

  public static Recorder.RecordingHeader getRecordingHeader(long workId, int recorderVersion) {
    Recorder.WorkAssignment workAssignment = Recorder.WorkAssignment.newBuilder()
        .addWork(
            Recorder.Work.newBuilder()
                .setWType(Recorder.WorkType.cpu_sample_work)
                .setCpuSample(Recorder.CpuSampleWork.newBuilder()
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
    Recorder.RecordingHeader recordingHeader = Recorder.RecordingHeader.newBuilder()
        .setRecorderVersion(recorderVersion)
        .setControllerVersion(2)
        .setControllerId(3)
        .setWorkAssignment(workAssignment)
        .build();

    return recordingHeader;
  }

  public static List<Recorder.StackSample> getPredefinedStackSamples(int traceId) {
    Recorder.StackSample stackSample1 = getMockStackSample(1, new char[]{'D', 'C', 'D', 'C', 'Y'});
    Recorder.StackSample stackSample2 = getMockStackSample(1, new char[]{'D', 'C', 'E', 'D', 'C', 'Y'});
    Recorder.StackSample stackSample3 = getMockStackSample(1, new char[]{'C', 'F', 'E', 'D', 'C', 'Y'});
    return Arrays.asList(stackSample1, stackSample2, stackSample3);
  }

  //TODO: Keeping the logic around in case we want to generate random samples in high volume later
  public static List<Recorder.StackSample> getRandomStackSamples(int traceId) {
    List<Recorder.StackSample> baseline = getPredefinedStackSamples(traceId);
    List<Recorder.StackSample> samples = new ArrayList<>();
    Random random = new Random();
    int samplesCount = baseline.size();
    int baselineSampleIndex = 0;
    while (samples.size() < samplesCount) {
      Recorder.StackSample baselineSample = baseline.get(baselineSampleIndex);
      Recorder.StackSample.Builder sampleBuilder = Recorder.StackSample.newBuilder()
          .setStartOffsetMicros(1000).setThreadId(1).addTraceId(traceId);

      List<Long> methodIds = new ArrayList(baselineSample.getFrameList().stream().map(frame -> frame.getMethodId()).collect(Collectors.toSet()));
      List<Recorder.Frame> frames = new ArrayList<>();
      for (int i = 0; i < baselineSample.getFrameCount(); i++) {
        Recorder.Frame baselineFrame = baselineSample.getFrame(i);
        long methodId = baselineFrame.getMethodId();
        if (random.nextInt(4 + (i * 2)) == 0) {
          methodId = methodIds.get(random.nextInt(methodIds.size()));
        }
        Recorder.Frame frame = Recorder.Frame.newBuilder().setBci(1).setLineNo(10).setMethodId(methodId).build();
        frames.add(frame);
      }
      Recorder.StackSample sample = sampleBuilder.addAllFrame(frames).build();
      samples.add(sample);

      baselineSampleIndex++;
      if (baselineSampleIndex == baseline.size()) {
        baselineSampleIndex = 0;
      }
    }

    return samples;
  }

  public static Recorder.RecordingChunk getMockCpuWseWithStackSample(Recorder.StackSampleWse currentStackSampleWse, Recorder.StackSampleWse prevStackSampleWse) {
    Recorder.RecordingChunk.Builder chunkBuilder = Recorder.RecordingChunk.newBuilder();
    chunkBuilder.setIndexedData(
        Recorder.IndexedData.newBuilder()
            .addAllMethodInfo(generateMethodIndex(currentStackSampleWse, prevStackSampleWse))
            .addAllTraceCtx(generateTraceIndex(currentStackSampleWse, prevStackSampleWse))
            .build())
        .addWse(Recorder.Wse.newBuilder()
            .setWType(Recorder.WorkType.cpu_sample_work)
            .setCpuSampleEntry(currentStackSampleWse)
            .build());
    return chunkBuilder.build();
  }

  private static List<Recorder.MethodInfo> generateMethodIndex(Recorder.StackSampleWse currentStackSampleWse, Recorder.StackSampleWse prevStackSampleWse) {
    Set<Long> currentMethodIds = uniqueMethodIdsInWse(currentStackSampleWse);
    Set<Long> prevMethodIds = uniqueMethodIdsInWse(prevStackSampleWse);
    Set<Long> newMethodIds = Sets.difference(currentMethodIds, prevMethodIds);
    return newMethodIds.stream()
        .map(mId -> Recorder.MethodInfo.newBuilder()
            .setFileName("").setClassFqdn("").setSignature("()")
            .setMethodId(mId)
            .setMethodName(String.valueOf((char) mId.intValue()))
            .build())
        .collect(Collectors.toList());
  }

  private static List<Recorder.TraceContext> generateTraceIndex(Recorder.StackSampleWse currentStackSampleWse, Recorder.StackSampleWse prevStackSampleWse) {
    Set<Integer> currentTraceIds = currentStackSampleWse.getStackSampleList().stream().flatMap(stackSample -> stackSample.getTraceIdList().stream()).collect(Collectors.toSet());
    Set<Integer> prevTraceIds = prevStackSampleWse == null
        ? new HashSet<>()
        : prevStackSampleWse.getStackSampleList().stream().flatMap(stackSample -> stackSample.getTraceIdList().stream()).collect(Collectors.toSet());
    Set<Integer> newTraceIds = Sets.difference(currentTraceIds, prevTraceIds);
    return newTraceIds.stream()
        .map(tId -> Recorder.TraceContext.newBuilder()
            .setCoveragePct(5)
            .setTraceId(tId)
            .setTraceName(String.valueOf(tId))
            .setIsGenerated(false)
            .build())
        .collect(Collectors.toList());
  }

  //dummyMethods is a char array. each method name is just a single character. method id is character's numeric repr
  //returned frames are in the same order as method names in input array
  private static List<Recorder.Frame> getMockFrames(char[] dummyMethods) {
    List<Recorder.Frame> frames = new ArrayList<>();
    for (char dummyMethod : dummyMethods) {
      frames.add(Recorder.Frame.newBuilder()
          .setMethodId((int) (dummyMethod))
          .setBci(1).setLineNo(10)
          .build());
    }
    return frames;
  }

  private static Recorder.StackSample getMockStackSample(int traceId, char[] dummyMethods) {
    return Recorder.StackSample.newBuilder()
        .setStartOffsetMicros(1000).setThreadId(1).setSnipped(true)
        .addTraceId(traceId)
        .addAllFrame(getMockFrames(dummyMethods))
        .build();
  }

  private static Set<Long> uniqueMethodIdsInWse(Recorder.StackSampleWse stackSampleWse) {
    if (stackSampleWse == null) {
      return new HashSet<>();
    }
    return stackSampleWse.getStackSampleList().stream()
        .flatMap(stackSample -> stackSample.getFrameList().stream())
        .map(frame -> frame.getMethodId())
        .collect(Collectors.toSet());
  }
}
