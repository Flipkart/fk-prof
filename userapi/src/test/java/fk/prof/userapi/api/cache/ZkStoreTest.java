package fk.prof.userapi.api.cache;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.ProfileStoreAPIImpl;
import fk.prof.userapi.proto.LoadInfoEntities;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.junit.*;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.mockito.Mockito.*;

/**
 * Created by gaurav.ashok on 14/08/17.
 */
public class ZkStoreTest {

    static {
        UserapiConfigManager.setDefaultSystemProperties();
    }

    private static final int zkPort = 2191;
    private static ZonedDateTime beginning = ZonedDateTime.now(Clock.systemUTC());

    private static Configuration config;
    private static TestingServer zookeeper;
    private static CuratorFramework curator;

    private ZkLoadInfoStore zkStore;
    private Supplier<List<AggregatedProfileNamingStrategy>> loadedProfiles = Mockito.mock(Supplier.class);
    private boolean zkDown = false;

    @BeforeClass
    public static void beforeClass() throws Exception {
        config = UserapiConfigManager.loadConfig(ProfileStoreAPIImpl.class.getClassLoader().getResource("userapi-conf.json").getFile());

        InstanceSpec instanceSpec = new InstanceSpec(null, zkPort, -1, -1, true, -1, 1000, -1);
        zookeeper = new TestingServer(instanceSpec, true);

        Configuration.CuratorConfig curatorConfig = config.getCuratorConfig();
        curator = CuratorFrameworkFactory.builder()
            .connectString("127.0.0.1:" + zkPort)
            .retryPolicy(new RetryOneTime(1000))
            .connectionTimeoutMs(curatorConfig.getConnectionTimeoutMs())
            .sessionTimeoutMs(curatorConfig.getSessionTimeoutMs())
            .namespace(curatorConfig.getNamespace())
            .build();

        curator.start();
        curator.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        curator.close();
        zookeeper.close();
    }

    @Before
    public void beforeTest() throws Exception {
        zkStore = new ZkLoadInfoStore(curator, "127.0.0.1", 8080, loadedProfiles);
        zkStore.ensureBasePathExists();
    }

    @After
    public void afterTest() throws Exception {
        if(curator.getZookeeperClient().isConnected()) {
            cleanUpZookeeper();
        }
    }

    @Test
    public void testBasic() throws Exception {
        AggregatedProfileNamingStrategy profileName1 = pn("proc1", dt(0));

        try(AutoCloseable lock1 = zkStore.getLock()) {
            zkStore.updateProfileResidencyInfo(profileName1, false);
        }

        LoadInfoEntities.NodeLoadInfo loadInfo = zkStore.readNodeLoadInfo();
        LoadInfoEntities.ProfileResidencyInfo residencyInfo = zkStore.readProfileResidencyInfo(profileName1);

        Assert.assertEquals(1, loadInfo.getProfilesLoaded());
        Assert.assertEquals("127.0.0.1", residencyInfo.getIp());
        Assert.assertEquals(8080, residencyInfo.getPort());

        zkStore.removeProfileResidencyInfo(profileName1, true);
        Assert.assertEquals(0, zkStore.readNodeLoadInfo().getProfilesLoaded());
        Assert.assertNull(zkStore.readProfileResidencyInfo(profileName1));
    }

    @Test
    public void testTransact() throws Exception {
        Assert.assertNull(curator.checkExists().forPath("/hello"));
        Collection<CuratorTransactionResult> res = null;
        try {
              res = curator.inTransaction().check().forPath("/nodesInfo").and().check().forPath("/nodesInfo2").and().commit();
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
        }
        System.out.println(res);
    }

    @Test(timeout = 10000)
    public void testConnectionStateTransition() throws Exception {
        AggregatedProfileNamingStrategy profileName1 = pn("proc1", dt(0)),
            profileName2 = pn("proc2", dt(0));

        zkStore.updateProfileResidencyInfo(profileName1, false);
        zkStore.updateProfileResidencyInfo(profileName2, false);

        doReturn(Arrays.asList(profileName1, profileName2)).when(loadedProfiles).get();

        try {
            bringDownZk();
            Thread.sleep(500);
            Exception caughtEx = null;
            try {
                zkStore.updateProfileResidencyInfo(profileName1, false);
            }
            catch (Exception e) {
                caughtEx = e;
            }

            Assert.assertNotNull(caughtEx);
            Assert.assertEquals(ZkStoreUnavailableException.class, caughtEx.getClass());

            // wait for some time, to let the session expire
            // weird that i have to wait for 15 sec here even though session timeout is 4sec.
            Thread.sleep(7000);
        }
        finally {
            bringUpZk();
        }

        // after connection lost we still expect the data to be there as part of reinitialization
        int retry = 2 * curator.getZookeeperClient().getZooKeeper().getSessionTimeout() / 1000;
        while(retry > 0 && zkStore.getState() == ZkLoadInfoStore.ConnectionState.Disconnected) {
            retry--;
        }

        Assert.assertEquals(ZkLoadInfoStore.ConnectionState.Connected, zkStore.getState());
        Assert.assertEquals(2, zkStore.readNodeLoadInfo().getProfilesLoaded());
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
            curator.blockUntilConnected(config.getCuratorConfig().getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            zkDown = false;
        }
    }

    private void cleanUpZookeeper() throws Exception {
        List<String> profileNodes = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        if(curator.checkExists().forPath("/profilesLoadStatus") != null) {
            profileNodes.addAll(curator.getChildren().forPath("/profilesLoadStatus"));
        }

        if(curator.checkExists().forPath("/nodesInfo") != null) {
            nodes.addAll(curator.getChildren().forPath("/nodesInfo"));
        }

        for(String path : profileNodes) {
            curator.delete().forPath("/profilesLoadStatus/" + path);
        }
        for(String path : nodes) {
            curator.delete().forPath("/nodesInfo/" + path);
        }
    }

    private ZonedDateTime dt(int offsetInSec) {
        return beginning.plusSeconds(offsetInSec);
    }

    private AggregatedProfileNamingStrategy pn(String procId, ZonedDateTime dt) {
        return new AggregatedProfileNamingStrategy(config.getProfilesBaseDir(), 1, "app1", "cluster1", procId, dt, 1200, AggregatedProfileModel.WorkType.cpu_sample_work);
    }
}
