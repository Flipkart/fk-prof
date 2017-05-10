package fk.prof.backend.model.policy.impl;

import fk.prof.backend.MockData;
import fk.prof.backend.model.policy.PolicyStore;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.Timing;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import policy.PolicyDetails;
import recording.Recorder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test for ZKWithCachePolicyStore
 * Created by rohit.patiyal on 09/03/17.
 */
@RunWith(VertxUnitRunner.class)
public class ZKWithCachePolicyStoreTest {

  private static final String policyPath = "/policy";
  private TestingServer testingServer;
  private CuratorFramework curatorClient;
  private PolicyStore policyStore;

  @Before
  public void setUp() throws Exception {
    testingServer = new TestingServer();
    Timing timing = new Timing();
    curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
    curatorClient.start();
    curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
    curatorClient.create().forPath(policyPath);
    policyStore = new ZKWithCachePolicyStore(curatorClient, policyPath);
  }

  @After
  public void tearDown() throws Exception {
    curatorClient.close();
    testingServer.close();
  }

  @Test(timeout = 10000)
  public void testGetPoliciesGivenAppId(TestContext context) throws Exception {
    final Async async = context.async();

    // PRE
    CompletableFuture f1 = policyStore.setPolicy(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setPolicy(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setPolicy(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));

    Map<String, Map<Object, Object>> testPairs = new HashMap<String, Map<Object, Object>>() {
      {
        put(MockData.mockProcessGroups.get(0).getAppId(),
            new HashMap<Object, Object>() {
              {
                put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
                put(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
                put(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));
              }
            });
        put("", new HashMap<>());
        put(null, new HashMap<>());
      }
    };
    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> {

      //TESTS
      for (Map.Entry<String, Map<Object, Object>> testPair : testPairs.entrySet()) {
        Map<Recorder.ProcessGroup, PolicyDetails> got = policyStore.getPolicies(testPair.getKey());
        context.assertTrue(got.equals(testPair.getValue()));
      }
      async.complete();
    });
  }

  @Test
  public void testGetPoliciesGivenAppIdClusterId(TestContext context) throws Exception {
    final Async async = context.async();
    // PRE
    CompletableFuture f1 = policyStore.setPolicy(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setPolicy(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setPolicy(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));

    Map<List<String>, Map<Object, Object>> testPairs = new HashMap<List<String>, Map<Object, Object>>() {
      {
        put(Arrays.asList(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(0).getCluster()),
            new HashMap<Object, Object>() {
              {
                put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
                put(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
              }
            });
        put(Arrays.asList("", ""), new HashMap<>());
        put(Arrays.asList(null, null), new HashMap<>());
      }
    };
    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> {

      //TESTS
      for (Map.Entry<List<String>, Map<Object, Object>> testPair : testPairs.entrySet()) {
        Map<Recorder.ProcessGroup, PolicyDetails> got = policyStore.getPolicies(testPair.getKey().get(0), testPair.getKey().get(1));
        context.assertTrue(got.equals(testPair.getValue()));
      }
      async.complete();
    });
  }

  @Test
  public void testGetPoliciesGivenAppIdClusterIdProcess(TestContext context) throws Exception {
    final Async async = context.async();
    // PRE
    CompletableFuture f1 = policyStore.setPolicy(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setPolicy(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setPolicy(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));

    Map<List<String>, Map<Object, Object>> testPairs = new HashMap<List<String>, Map<Object, Object>>() {
      {
        put(Arrays.asList(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(0).getCluster(), MockData.mockProcessGroups.get(0).getProcName()),
            new HashMap<Object, Object>() {
              {
                put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
              }
            });
        put(Arrays.asList("", "", ""), new HashMap<>());
        put(Arrays.asList(null, null, null), new HashMap<>());
      }
    };
    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> {

      //TESTS
      for (Map.Entry<List<String>, Map<Object, Object>> testPair : testPairs.entrySet()) {
        Map<Recorder.ProcessGroup, PolicyDetails> got = policyStore.getPolicies(testPair.getKey().get(0), testPair.getKey().get(1), testPair.getKey().get(2));
        context.assertTrue(got.equals(testPair.getValue()));
      }
      async.complete();
    });
  }

  @Test(timeout = 4000)
  public void testGetPolicy(TestContext context) throws Exception {
    final Async async = context.async();

    //PRE
    policyStore.setPolicy(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0)).whenComplete((aVoid, throwable) -> {

      //TEST
      context.assertTrue(policyStore.getPolicy(MockData.mockProcessGroups.get(0)) == MockData.mockPolicies.get(0));
      async.complete();
    });
  }

  @Test(timeout = 4000)
  public void testSetPolicy(TestContext context) throws Exception {
    final Async async = context.async();

    //TEST
    policyStore.setPolicy(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0)).whenComplete((aVoid, throwable) -> {
      context.assertTrue(throwable == null);
      async.complete();
    });
  }

  @Test(timeout = 4000)
  public void testPopulateCacheFromZK(TestContext context) throws Exception {
    final Async async = context.async();

    // PRE
    CompletableFuture f1 = policyStore.setPolicy(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setPolicy(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setPolicy(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));

    CompletableFuture.allOf(f1, f2, f3).whenCompleteAsync((aVoid, throwable) -> {
      ZKWithCachePolicyStore anotherPolicyStore = new ZKWithCachePolicyStore(curatorClient, policyPath);
      //TESTS
      try {
        Thread.sleep(2000);   //Waiting for the cache to get populated
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      int processGroupNum = 0;
      for (PolicyDetails expectedPolicy : MockData.mockPolicies) {
        context.assertTrue(anotherPolicyStore.getPolicy(MockData.mockProcessGroups.get(processGroupNum++)).equals(expectedPolicy));
      }
      async.complete();
    });
  }
}