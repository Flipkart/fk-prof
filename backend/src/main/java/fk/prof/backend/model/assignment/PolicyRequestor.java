package fk.prof.backend.model.assignment;

import com.codahale.metrics.Meter;
import fk.prof.idl.Backend;
import fk.prof.idl.Entities;
import io.vertx.core.Future;

@FunctionalInterface
public interface PolicyRequestor {
  Future<Backend.RecordingPolicy> get(Entities.ProcessGroup processGroup, Meter mtrSuccess, Meter mtrFailure);
}
