package fk.prof.backend.model.policy;

import policy.PolicyDetails;
import recording.Recorder;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for accessing the store containing policy information
 * Created by rohit.patiyal on 07/03/17.
 */
public interface PolicyStore {
  PolicyDetails getPolicy(Recorder.ProcessGroup processGroup);

  Map<Recorder.ProcessGroup, PolicyDetails> getPolicies(String appId);

  Map<Recorder.ProcessGroup, PolicyDetails> getPolicies(String appId, String clusterId);

  Map<Recorder.ProcessGroup, PolicyDetails> getPolicies(String appId, String clusterId, String process);

  CompletableFuture<Void> setPolicy(Recorder.ProcessGroup processGroup, PolicyDetails policyDetails);
}
