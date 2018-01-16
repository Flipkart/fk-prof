package fk.prof.backend.util.proto;

import fk.prof.idl.Backend;

public class BackendProtoUtil {
  public static String recordingPolicyCompactRepr(Backend.RecordingPolicy recordingPolicy) {
    return String.format("dur=%d,cov=%d,desc=%s", recordingPolicy.getDuration(), recordingPolicy.getCoveragePct(), recordingPolicy.getDescription());
  }

  public static String leaderDetailCompactRepr(Backend.LeaderDetail leaderDetail) {
    return leaderDetail == null ? null : String.format("%s:%s", leaderDetail.getHost(), leaderDetail.getPort());
  }
}
