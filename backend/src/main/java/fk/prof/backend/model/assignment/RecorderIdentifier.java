package fk.prof.backend.model.assignment;

import com.google.common.base.Preconditions;
import fk.prof.idl.Recorder;
import fk.prof.metrics.RecorderTag;

public class RecorderIdentifier {
  private final String ip;
  private final String hostname;
  private final String appId;
  private final String instanceGrp;
  private final String cluster;
  private final String instanceId;
  private final String procName;
  private final String vmId;
  private final String zone;
  private final String instanceType;

  private final RecorderTag recorderTag;

  public RecorderIdentifier(String ip, String hostname, String appId, String instanceGrp, String cluster,
                            String instanceId, String procName, String vmId, String zone, String instanceType) {
    this.ip = Preconditions.checkNotNull(ip);
    this.hostname = Preconditions.checkNotNull(hostname);
    this.appId = Preconditions.checkNotNull(appId);
    this.instanceGrp = Preconditions.checkNotNull(instanceGrp);
    this.cluster = Preconditions.checkNotNull(cluster);
    this.instanceId = Preconditions.checkNotNull(instanceId);
    this.procName = Preconditions.checkNotNull(procName);
    this.vmId = Preconditions.checkNotNull(vmId);
    this.zone = Preconditions.checkNotNull(zone);
    this.instanceType = Preconditions.checkNotNull(instanceType);
    this.recorderTag = new RecorderTag(this.ip, this.procName);
  }

  public static RecorderIdentifier from(Recorder.RecorderInfo recorderInfo) {
    return new RecorderIdentifier(recorderInfo.getIp(), recorderInfo.getHostname(), recorderInfo.getAppId(), recorderInfo.getInstanceGrp(),
        recorderInfo.getCluster(), recorderInfo.getInstanceId(), recorderInfo.getProcName(), recorderInfo.getVmId(),
        recorderInfo.getZone(), recorderInfo.getInstanceType());
  }

  public RecorderTag metricTag() {
    return this.recorderTag;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof RecorderIdentifier)) {
      return false;
    }

    RecorderIdentifier other = (RecorderIdentifier) o;
    return this.ip.equals(other.ip)
        && this.hostname.equals(other.hostname)
        && this.appId.equals(other.appId)
        && this.instanceGrp.equals(other.instanceGrp)
        && this.cluster.equals(other.cluster)
        && this.instanceId.equals(other.instanceId)
        && this.procName.equals(other.procName)
        && this.vmId.equals(other.vmId)
        && this.zone.equals(other.zone)
        && this.instanceType.equals(other.instanceType);
  }

  @Override
  public int hashCode() {
    final int PRIME = 31;
    int result = 1;
    result = result * PRIME + this.ip.hashCode();
    result = result * PRIME + this.hostname.hashCode();
    result = result * PRIME + this.appId.hashCode();
    result = result * PRIME + this.instanceGrp.hashCode();
    result = result * PRIME + this.cluster.hashCode();
    result = result * PRIME + this.instanceId.hashCode();
    result = result * PRIME + this.procName.hashCode();
    result = result * PRIME + this.vmId.hashCode();
    result = result * PRIME + this.zone.hashCode();
    result = result * PRIME + this.instanceType.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "ip=" + this.ip +
        ", zone=" + this.zone +
        ", host=" + this.hostname +
        ", proc=" + this.procName +
        ", cluster=" + this.cluster +
        ", vm=" + this.vmId +
        ", instance_type=" + instanceType;
  }
}
