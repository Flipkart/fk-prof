package fk.prof.backend.util.proto;

import fk.prof.idl.Profile;

public class ProfileProtoUtil {
  public static String recordingPolicyCompactRepr(Profile.RecordingPolicy recordingPolicy) {
    return String.format("dur=%d,cov=%d,desc=%s", recordingPolicy.getDuration(), recordingPolicy.getCoveragePct(), recordingPolicy.getDescription());
  }
}
