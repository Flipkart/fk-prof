package fk.prof.backend.model.assignment;

import com.codahale.metrics.Meter;
import fk.prof.idl.Entities;
import fk.prof.idl.Profile;
import io.vertx.core.Future;

@FunctionalInterface
public interface PolicyRequestor {
  Future<Profile.RecordingPolicy> get(Entities.ProcessGroup processGroup, Meter mtrSuccess, Meter mtrFailure);
}
