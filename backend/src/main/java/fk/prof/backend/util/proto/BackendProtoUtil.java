package fk.prof.backend.util.proto;

import fk.prof.backend.proto.BackendDTO;

public class BackendProtoUtil {
  public static String leaderDetailCompactRepr(BackendDTO.LeaderDetail leaderDetail) {
    return leaderDetail == null ? null : String.format("%s:%s", leaderDetail.getHost(), leaderDetail.getPort());
  }
}
