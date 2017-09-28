package fk.prof.backend.util.proto;

import fk.prof.backend.proto.BackendDTO;

public class BackendDTOProtoUtil {
  public static String recordingPolicyCompactRepr(BackendDTO.RecordingPolicy recordingPolicy) {
    return String.format("dur=%d,cov=%d,desc=%s", recordingPolicy.getDuration(), recordingPolicy.getCoveragePct(), recordingPolicy.getDescription());
  }
}
