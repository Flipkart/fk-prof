package fk.prof.backend.model.assignment.impl;

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.model.assignment.*;
import fk.prof.idl.Entities;
import fk.prof.metrics.MetricName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class AssociatedProcessGroupsImpl implements AssociatedProcessGroups, ProcessGroupDiscoveryContext {
  private final Map<Entities.ProcessGroup, ProcessGroupDetail> processGroupLookup = new ConcurrentHashMap<>();
  private final int thresholdForDefunctRecorderInSecs;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);

  public AssociatedProcessGroupsImpl(int thresholdForDefunctRecorderInSecs) {
    this.thresholdForDefunctRecorderInSecs = thresholdForDefunctRecorderInSecs;
    try {
      metricRegistry.register(MetricName.Backend_Association_Count.get(),
          new CachedGauge<Integer>(1, TimeUnit.MINUTES) {
            @Override
            protected Integer loadValue() {
              return processGroupLookup.size();
            }
          });
    } catch (IllegalArgumentException ex) {
      //Metric already registered, ignore
    }
  }


  //Called by backend daemon thread when load is reported to leader and leader responds with associations
  @Override
  public void updateProcessGroupAssociations(Entities.ProcessGroups processGroups, BiConsumer<ProcessGroupContextForScheduling, ProcessGroupAssociationResult> postUpdateAction) {
    //Remove process group associations which are not returned by leader
    for(Entities.ProcessGroup processGroup: this.processGroupLookup.keySet()) {
      if(!processGroups.getProcessGroupList().contains(processGroup)) {
        ProcessGroupDetail processGroupDetail = this.processGroupLookup.remove(processGroup);
        postUpdateAction.accept(processGroupDetail, ProcessGroupAssociationResult.REMOVED);
      }
    }

    //Add process group associations which are returned by leader
    for(Entities.ProcessGroup processGroup: processGroups.getProcessGroupList()) {
      ProcessGroupDetail existingValue = this.processGroupLookup.putIfAbsent(processGroup, new ProcessGroupDetail(processGroup, thresholdForDefunctRecorderInSecs));
      if(existingValue == null) {
        postUpdateAction.accept(this.processGroupLookup.get(processGroup), ProcessGroupAssociationResult.ADDED);
      }
    }
  }

  @Override
  public ProcessGroupContextForPolling getProcessGroupContextForPolling(Entities.ProcessGroup processGroup) {
    return this.processGroupLookup.get(processGroup);
  }
}
