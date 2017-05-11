package fk.prof.backend.model.policy;

import fk.prof.backend.proto.BackendDTO;
import policy.PolicyDetails;
import recording.Recorder;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for accessing the store containing policy information
 * Created by rohit.patiyal on 07/03/17.
 */
public interface PolicyStore {
  void init();

  PolicyDetails getUserPolicy(Recorder.ProcessGroup processGroup);

  Map<String, Map<String, Map<String, PolicyDetails>>> getUserPolicies(String appId);

  Map<String, Map<String, Map<String, PolicyDetails>>> getUserPolicies(String appId, String clusterId);

  Map<String, Map<String, Map<String, PolicyDetails>>> getUserPolicies(String appId, String clusterId, String process);

  CompletableFuture<Void> setUserPolicy(Recorder.ProcessGroup processGroup, PolicyDetails policyDetails);

  CompletableFuture<Void> removeUserPolicy(Recorder.ProcessGroup processGroup, String admin);

  BackendDTO.RecordingPolicy getRecordingPolicy(Recorder.ProcessGroup processGroup);

  void putRecordingPolicy(Recorder.ProcessGroup processGroup, BackendDTO.RecordingPolicy recordingPolicy);
}
