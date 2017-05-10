package fk.prof.backend.model.policy.impl;

import policy.PolicyDetails;
import recording.Recorder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In memory replica of the ZK Policy store
 * Created by rohit.patiyal on 09/03/17.
 */
class CachePolicyStore {

    private final Map<String, Map<String, Map<String, PolicyDetails>>> processGroupAsTreeToPolicyLookup = new ConcurrentHashMap<>();

    public Map<Recorder.ProcessGroup, PolicyDetails> get(String appId) {
        Map<Recorder.ProcessGroup, PolicyDetails> processGroupPolicyDetailsMap = new HashMap<>();
        Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
        if (clusterIdProcToPolicyLookup != null) {
            for (Map.Entry<String, Map<String, PolicyDetails>> clusterProcToPolicy : clusterIdProcToPolicyLookup.entrySet()) {
                for (Map.Entry<String, PolicyDetails> procToPolicy : clusterProcToPolicy.getValue().entrySet()) {
                    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterProcToPolicy.getKey()).setProcName(procToPolicy.getKey()).build();
                    processGroupPolicyDetailsMap.put(processGroup, procToPolicy.getValue());
                }
            }
        }
        return processGroupPolicyDetailsMap;
    }

    public Map<Recorder.ProcessGroup, PolicyDetails> get(String appId, String clusterId) {
        Map<Recorder.ProcessGroup, PolicyDetails> processGroupPolicyDetailsMap = new HashMap<>();
        Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
        if (clusterIdProcToPolicyLookup != null) {
            Map<String, PolicyDetails> procToPolicyLookup = clusterIdProcToPolicyLookup.get(clusterId);
            if (procToPolicyLookup != null) {
                for (Map.Entry<String, PolicyDetails> procToPolicy : procToPolicyLookup.entrySet()) {
                    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(procToPolicy.getKey()).build();
                    processGroupPolicyDetailsMap.put(processGroup, procToPolicy.getValue());
                }
            }
        }
        return processGroupPolicyDetailsMap;
    }

    public Map<Recorder.ProcessGroup, PolicyDetails> get(String appId, String clusterId, String process) {
        Map<Recorder.ProcessGroup, PolicyDetails> processGroupPolicyDetailsMap = new HashMap<>();
        Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
        if (clusterIdProcToPolicyLookup != null) {
            Map<String, PolicyDetails> procToPolicyLookup = clusterIdProcToPolicyLookup.get(clusterId);
            if (procToPolicyLookup != null) {
                if (procToPolicyLookup.containsKey(process)) {
                    Recorder.ProcessGroup processGroup = Recorder.ProcessGroup.newBuilder().setAppId(appId).setCluster(clusterId).setProcName(process).build();
                    processGroupPolicyDetailsMap.put(processGroup, procToPolicyLookup.get(process));
                }
            }
        }
        return processGroupPolicyDetailsMap;
    }

    public void put(Recorder.ProcessGroup processGroup, PolicyDetails policyDetails) {
        String appId = processGroup.getAppId();
        String clusterId = processGroup.getCluster();
        String process = processGroup.getProcName();
        processGroupAsTreeToPolicyLookup.putIfAbsent(appId, new ConcurrentHashMap<>());
        processGroupAsTreeToPolicyLookup.get(appId).putIfAbsent(clusterId, new ConcurrentHashMap<>());
        processGroupAsTreeToPolicyLookup.get(appId).get(clusterId).put(process, policyDetails);
    }
}
