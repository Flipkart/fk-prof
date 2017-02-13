package fk.prof.backend.util;

import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.backend.proto.BackendDTO;
import recording.Recorder;

public class ProtoUtil {
  public static AggregatedProfileModel.WorkType mapRecorderToAggregatorWorkType(Recorder.WorkType recorderWorkType) {
    return AggregatedProfileModel.WorkType.forNumber(recorderWorkType.getNumber());
  }

  public static Recorder.ProcessGroup mapRecorderToBackendProcessGroup(Recorder.ProcessGroup recorderProcessGroup) {
    return Recorder.ProcessGroup.newBuilder()
        .setAppId(recorderProcessGroup.getAppId())
        .setCluster(recorderProcessGroup.getCluster())
        .setProcName(recorderProcessGroup.getProcName())
        .build();
  }

  public static String processGroupCompactRepr(Recorder.ProcessGroup processGroup) {
    return String.format("%s,%s,%s", processGroup.getAppId(), processGroup.getCluster(), processGroup.getProcName());
  }
}
