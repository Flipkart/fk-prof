package fk.prof.backend.model.policy;

import fk.prof.backend.proto.PolicyDTO;
import io.vertx.core.Future;
import recording.Recorder;

/**
 * Interface for accessing the store containing policy information
 * Created by rohit.patiyal on 18/05/17.
 */
public interface PolicyStoreAPI {
    /**
     * Gets VersionedPolicyDetails currently stored for the processGroup
     *
     * @param processGroup of which the policy is to be retrieved
     * @return versionedPolicyDetail for the processGroup
     */
    PolicyDTO.VersionedPolicyDetails getVersionedPolicy(Recorder.ProcessGroup processGroup);

    /**
     * Creates a VersionedPolicyDetails for the processGroup supplied if there exists no policy for it previously
     * @param processGroup of which the policy mapping is to be created
     * @param versionedPolicyDetails to be set for the processGroup
     * @return a void future which contains the created versionedPolicyDetails
     */
    Future<PolicyDTO.VersionedPolicyDetails> createVersionedPolicy(Recorder.ProcessGroup processGroup, PolicyDTO.VersionedPolicyDetails versionedPolicyDetails);

    /**
     * Updates a VersionedPolicyDetails for the processGroup supplied if there exists a policy for it previously
     * @param processGroup of which the policy mapping is to be updated
     * @param versionedPolicyDetails to be set for the processGroup
     * @return a void future which contains the created versionedPolicyDetails
     */
    Future<PolicyDTO.VersionedPolicyDetails> updateVersionedPolicy(Recorder.ProcessGroup processGroup, PolicyDTO.VersionedPolicyDetails versionedPolicyDetails);
}
