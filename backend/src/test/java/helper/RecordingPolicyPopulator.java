package helper;

import fk.prof.backend.ConfigManager;
import fk.prof.backend.Configuration;
import fk.prof.backend.util.PathNamingUtil;
import fk.prof.idl.Entities;
import fk.prof.idl.PolicyEntities;
import fk.prof.idl.WorkEntities;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class RecordingPolicyPopulator {
  public static void main(String[] args) throws Exception {
    String appId = "a1", cluster = "c1", process = "p1";
    int policyDuration = 60, coveragePct = 100;

    URL resource = RecordingPolicyPopulator.class.getClassLoader().getResource("config.json");
    Path configFilePath = Paths.get(resource.toURI()).toAbsolutePath();
    Configuration configuration = ConfigManager.loadConfig(configFilePath.toAbsolutePath().toString());
    String policyStorePath = "/" + configuration.getPolicyBaseDir();

    Configuration.CuratorConfig curatorConfig = configuration.getCuratorConfig();
    CuratorFramework curator = CuratorFrameworkFactory.builder()
        .connectString(curatorConfig.getConnectionUrl())
        .retryPolicy(new ExponentialBackoffRetry(1000, curatorConfig.getMaxRetries()))
        .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
        .sessionTimeoutMs(curatorConfig.getSessionTineoutMs())
        .namespace(curatorConfig.getNamespace())
        .build();
    curator.start();
    curator.blockUntilConnected(10000, TimeUnit.MILLISECONDS);

    String zkNamespace = curatorConfig.getNamespace();
    try {
      curator.create().forPath("/" + zkNamespace);
    } catch (KeeperException.NodeExistsException ex) {}
    try {
      curator.create().forPath("/" + zkNamespace + policyStorePath);
    } catch (KeeperException.NodeExistsException ex) {}

    Entities.ProcessGroup pg = Entities.ProcessGroup.newBuilder().setAppId(appId).setCluster(cluster).setProcName(process).build();
    String policyNodePath = PathNamingUtil.getPolicyNodePath(pg, policyStorePath, configuration.getPolicyVersion());
    String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    PolicyEntities.PolicyDetails policyDetails = PolicyEntities.PolicyDetails.newBuilder()
        .setCreatedAt(currentTime)
        .setModifiedAt(currentTime)
        .setModifiedBy(RecordingPolicyPopulator.class.getName())
        .setPolicy(PolicyEntities.Policy.newBuilder()
          .setSchedule(PolicyEntities.Schedule.newBuilder()
              .setDuration(policyDuration)
              .setPgCovPct(coveragePct)
              .setAfter(currentTime))
          .setDescription("Dummy policy")
//          .addWork(WorkEntities.Work.newBuilder()
//              .setWType(WorkEntities.WorkType.cpu_sample_work)
//              .setCpuSample(WorkEntities.CpuSampleWork.newBuilder()
//                  .setFrequency(50)
//                  .setMaxFrames(16))
//              .build()))
            .addWork(WorkEntities.Work.newBuilder()
                .setWType(WorkEntities.WorkType.io_trace_work)
                .setIoTrace(WorkEntities.IOTraceWork.newBuilder()
                    .setLatencyThresholdMs(1)
                    .setSerializationFlushThreshold(10)
                    .setMaxFrames(16))
                .build()))
        .build();

    String policyNodePathWithNodeName = curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
        .forPath(policyNodePath, policyDetails.toByteArray());
    Integer newVersion = Integer.parseInt(ZKPaths.getNodeFromPath(policyNodePathWithNodeName));

    PolicyEntities.VersionedPolicyDetails versionedPolicyDetails = PolicyEntities.VersionedPolicyDetails.newBuilder()
        .setPolicyDetails(policyDetails)
        .setVersion(newVersion)
        .build();

    System.out.println(versionedPolicyDetails);
    curator.close();
  }
}
