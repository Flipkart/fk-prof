package fk.prof.backend.util.proto;

import fk.prof.idl.Backend;

public class BackendProtoUtil {
  public static String leaderDetailCompactRepr(Backend.LeaderDetail leaderDetail) {
    return leaderDetail == null ? null : String.format("%s:%s", leaderDetail.getHost(), leaderDetail.getPort());
  }
}
