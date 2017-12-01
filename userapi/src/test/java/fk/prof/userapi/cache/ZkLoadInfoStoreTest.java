package fk.prof.userapi.cache;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.proto.LoadInfoEntities;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.listen.ListenerContainer;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.junit.*;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Created by gaurav.ashok on 14/08/17.
 */
public class ZkLoadInfoStoreTest {

    static {
        UserapiConfigManager.setDefaultSystemProperties();
    }

    private static final int zkPort = 2191;
    private static ZonedDateTime beginning = ZonedDateTime.now(Clock.systemUTC());

    private static Configuration config;
    private static TestingServer zookeeper;
    private static CuratorFramework curatorClient;

    private ZkLoadInfoStore zkLoadInfoStore;
    private Runnable loadedProfiles = Mockito.mock(Runnable.class);
    private boolean zkDown = false;

    @BeforeClass
    public static void beforeClass() throws Exception {
        config = UserapiConfigManager.loadConfig(ProfileStoreAPIImpl.class.getClassLoader().getResource("userapi-conf.json").getFile());

        InstanceSpec instanceSpec = new InstanceSpec(null, zkPort, -1, -1, true, -1, 1000, -1);
        zookeeper = new TestingServer(instanceSpec, true);

        Configuration.CuratorConfig curatorConfig = config.getCuratorConfig();
        curatorClient = CuratorFrameworkFactory.builder()
            .connectString("127.0.0.1:" + zkPort)
            .retryPolicy(new RetryOneTime(1000))
            .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
            .sessionTimeoutMs(curatorConfig.getSessionTimeoutMs())
            .namespace(curatorConfig.getNamespace())
            .build();

        curatorClient.start();
        curatorClient.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        curatorClient.close();
        zookeeper.close();
    }

    @Before
    public void beforeTest() throws Exception {
        zkLoadInfoStore = new ZkLoadInfoStore(curatorClient, "127.0.0.1", 8080, loadedProfiles);
        zkLoadInfoStore.init();
    }

    @After
    public void afterTest() throws Exception {
        if(curatorClient.getZookeeperClient().isConnected()) {
            cleanUpZookeeper();
        }
        //Clearing all connection listeners added to curatorClient in beforeTest zkLoadInfoStore creation
        ((ListenerContainer<ConnectionStateListener>) curatorClient.getConnectionStateListenable()).clear();
    }

    @Test
    public void testBasic() throws Exception {
        AggregatedProfileNamingStrategy profileName1 = pn("proc1", dt(0));

        try(AutoCloseable ignored = zkLoadInfoStore.getLock()) {
            zkLoadInfoStore.updateProfileResidencyInfo(profileName1, false);
        }

        LoadInfoEntities.NodeLoadInfo loadInfo = zkLoadInfoStore.readNodeLoadInfo();
        LoadInfoEntities.ProfileResidencyInfo residencyInfo = zkLoadInfoStore.readProfileResidencyInfo(profileName1);

        Assert.assertEquals(1, loadInfo.getProfilesLoaded());
        Assert.assertEquals("127.0.0.1", residencyInfo.getIp());
        Assert.assertEquals(8080, residencyInfo.getPort());

        zkLoadInfoStore.removeProfileResidencyInfo(profileName1, true);
        Assert.assertEquals(0, zkLoadInfoStore.readNodeLoadInfo().getProfilesLoaded());
        Assert.assertNull(zkLoadInfoStore.readProfileResidencyInfo(profileName1));
    }

    @Test(timeout = 10000)
    public void testConnectionStateTransition() throws Exception {
        AggregatedProfileNamingStrategy profileName1 = pn("proc1", dt(0)),
            profileName2 = pn("proc2", dt(0));

        zkLoadInfoStore.updateProfileResidencyInfo(profileName1, false);
        zkLoadInfoStore.updateProfileResidencyInfo(profileName2, false);

        try {
            bringDownZk();
            Thread.sleep(500);
            Exception caughtEx = null;
            try {
                zkLoadInfoStore.updateProfileResidencyInfo(profileName1, false);
            }
            catch (Exception e) {
                caughtEx = e;
            }

            Assert.assertNotNull(caughtEx);
            Assert.assertEquals(ZkStoreUnavailableException.class, caughtEx.getClass());

            // wait for some time, to let the session expire, sessionTimeout occurs after 4 secs
            Thread.sleep(4000);
        }
        finally {
            bringUpZk();
        }
        // after connection lost we still expect the data to be reset to be 0
        int retry = 2 * curatorClient.getZookeeperClient().getZooKeeper().getSessionTimeout() / 1000;
        while(retry > 0 && zkLoadInfoStore.getState() == ZkLoadInfoStore.ConnectionState.Disconnected) {
            Thread.sleep(1000);
            retry--;
        }

        Assert.assertEquals(ZkLoadInfoStore.ConnectionState.Connected, zkLoadInfoStore.getState());
        Assert.assertEquals(0, zkLoadInfoStore.readNodeLoadInfo().getProfilesLoaded());
    }

    private void bringDownZk() throws Exception {
        if(!zkDown) {
            zkDown = true;
            zookeeper.stop();
        }
    }

    private void bringUpZk() throws Exception {
        if(zkDown) {
            zookeeper.restart();
            curatorClient.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            zkDown = false;
        }
    }

    private void cleanUpZookeeper() throws Exception {
        List<String> profileNodes = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        if(curatorClient.checkExists().forPath("/profilesLoadStatus") != null) {
            profileNodes.addAll(curatorClient.getChildren().forPath("/profilesLoadStatus"));
        }

        if(curatorClient.checkExists().forPath("/nodesInfo") != null) {
            nodes.addAll(curatorClient.getChildren().forPath("/nodesInfo"));
        }

        for(String path : profileNodes) {
            curatorClient.delete().forPath("/profilesLoadStatus/" + path);
        }
        for(String path : nodes) {
            curatorClient.delete().forPath("/nodesInfo/" + path);
        }
    }

    private ZonedDateTime dt(int offsetInSec) {
        return beginning.plusSeconds(offsetInSec);
    }

    private AggregatedProfileNamingStrategy pn(String procId, ZonedDateTime dt) {
        return new AggregatedProfileNamingStrategy(config.getProfilesBaseDir(), 1, "app1", "cluster1", procId, dt, 1200, AggregatedProfileModel.WorkType.cpu_sample_work);
    }
}
