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

  public Map<String, Map<String, Map<String, PolicyDetails>>> get(String appId) {
    Map<String, Map<String, Map<String, PolicyDetails>>> policies = new HashMap<>();
    Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
    if (clusterIdProcToPolicyLookup != null) {
      policies.put(appId, clusterIdProcToPolicyLookup);
    }
    return policies;
  }

  public Map<String, Map<String, Map<String, PolicyDetails>>> get(String appId, String clusterId) {
    Map<String, Map<String, Map<String, PolicyDetails>>> policies = new HashMap<>();
    Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
    if (clusterIdProcToPolicyLookup != null) {
      Map<String, PolicyDetails> procToPolicyLookup = clusterIdProcToPolicyLookup.get(clusterId);
      if (procToPolicyLookup != null) {
        policies.put(appId, new HashMap<String, Map<String, PolicyDetails>>() {{
          put(clusterId, procToPolicyLookup);
        }});
      }
    }
    return policies;
  }

  public Map<String, Map<String, Map<String, PolicyDetails>>> get(String appId, String clusterId, String process) {
    Map<String, Map<String, Map<String, PolicyDetails>>> policies = new HashMap<>();
    Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
    if (clusterIdProcToPolicyLookup != null) {
      Map<String, PolicyDetails> procToPolicyLookup = clusterIdProcToPolicyLookup.get(clusterId);
      if (procToPolicyLookup != null) {
        if (procToPolicyLookup.containsKey(process)) {
          policies.put(appId, new HashMap<String, Map<String, PolicyDetails>>() {{
            put(clusterId, new HashMap<String, PolicyDetails>() {{
                  put(process, procToPolicyLookup.get(process));
                }}
            );
          }});
        }
      }
    }
    return policies;
  }

  public void put(Recorder.ProcessGroup processGroup, PolicyDetails policyDetails) {
    String appId = processGroup.getAppId();
    String clusterId = processGroup.getCluster();
    String process = processGroup.getProcName();
    processGroupAsTreeToPolicyLookup.putIfAbsent(appId, new ConcurrentHashMap<>());
    processGroupAsTreeToPolicyLookup.get(appId).putIfAbsent(clusterId, new ConcurrentHashMap<>());
    processGroupAsTreeToPolicyLookup.get(appId).get(clusterId).put(process, policyDetails);
  }


  public void remove(Recorder.ProcessGroup processGroup) {
    String appId = processGroup.getAppId();
    String clusterId = processGroup.getCluster();
    String process = processGroup.getProcName();
    Map<String, Map<String, PolicyDetails>> clusterIdProcToPolicyLookup = processGroupAsTreeToPolicyLookup.get(appId);
    if (clusterIdProcToPolicyLookup != null) {
      Map<String, PolicyDetails> procToPolicyLookup = clusterIdProcToPolicyLookup.get(clusterId);
      if (procToPolicyLookup != null) {
        if (procToPolicyLookup.containsKey(process)) {
          procToPolicyLookup.remove(process);
        }
        if (procToPolicyLookup.isEmpty()) {
          clusterIdProcToPolicyLookup.remove(clusterId);
        }
        if (clusterIdProcToPolicyLookup.isEmpty()) {
          processGroupAsTreeToPolicyLookup.remove(appId);
        }
      }
    }
  }
}
