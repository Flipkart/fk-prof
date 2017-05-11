package fk.prof.backend.model.policy.impl;

import fk.prof.backend.mock.MockPolicyData;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test for ZKWithCacheBasedPolicyStore
 * Created by rohit.patiyal on 09/03/17.
 */
@RunWith(VertxUnitRunner.class)
public class ZKWithCacheBasedPolicyStoreTest {

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
    policyStore = new ZKWithCacheBasedPolicyStore(curatorClient, policyPath);
    policyStore.init();
  }

  @After
  public void tearDown() throws Exception {
    curatorClient.close();
    testingServer.close();
  }

  @Test(timeout = 10000)
  public void testGetAssociatedPoliciesGivenAppId(TestContext context) throws Exception {
    final Async async = context.async();

    // PRE
    CompletableFuture f1 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(2), MockPolicyData.mockPolicies.get(2));

    Map<String, Map<String, Map<String, Map<String, PolicyDetails>>>> testPairs = new HashMap<String,Map<String, Map<String, Map<String, PolicyDetails>>>>() {
      {
        put(MockPolicyData.mockProcessGroups.get(0).getAppId(),
                new HashMap<String, Map<String, Map<String, PolicyDetails>>>() {{
                  put(MockPolicyData.mockProcessGroups.get(0).getAppId(), new HashMap<String, Map<String, PolicyDetails>>() {{
                  put(MockPolicyData.mockProcessGroups.get(0).getCluster(), new HashMap<String, PolicyDetails>() {{
                    put(MockPolicyData.mockProcessGroups.get(0).getProcName(), MockPolicyData.mockPolicies.get(0));
                    put(MockPolicyData.mockProcessGroups.get(1).getProcName(), MockPolicyData.mockPolicies.get(1));
                  }});
                  put(MockPolicyData.mockProcessGroups.get(2).getCluster(), new HashMap<String, PolicyDetails>() {{
                    put(MockPolicyData.mockProcessGroups.get(2).getProcName(), MockPolicyData.mockPolicies.get(2));
                  }});
                }});
              }
            });
        put("", new HashMap<>());
        put(MockPolicyData.mockProcessGroups.get(3).getAppId(), new HashMap<>());
        put(null, new HashMap<>());
      }
    };
    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> {

      //TESTS
      for (Map.Entry<String, Map<String, Map<String, Map<String, PolicyDetails>>>>  testPair : testPairs.entrySet()) {
        Map<String, Map<String, Map<String, PolicyDetails>>> got = policyStore.getUserPolicies(testPair.getKey());
        context.assertTrue(got.equals(testPair.getValue()));
      }
      async.complete();
    });
  }

  @Test
  public void testGetAssociatedPoliciesGivenAppIdClusterId(TestContext context) throws Exception {
    final Async async = context.async();
    // PRE
    CompletableFuture f1 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(2), MockPolicyData.mockPolicies.get(2));

    Map<List<String>, Map<Object, Object>> testPairs = new HashMap<List<String>, Map<Object, Object>>() {
      {
        put(Arrays.asList(MockPolicyData.mockProcessGroups.get(0).getAppId(), MockPolicyData.mockProcessGroups.get(0).getCluster()),
            new HashMap<Object, Object>() {
              {
                put(MockPolicyData.mockProcessGroups.get(0).getAppId(), new HashMap<Object, Object>() {
                  {
                    put(MockPolicyData.mockProcessGroups.get(0).getCluster(), new HashMap<Object, Object>() {
                      {
                        put(MockPolicyData.mockProcessGroups.get(0).getProcName(), MockPolicyData.mockPolicies.get(0));
                        put(MockPolicyData.mockProcessGroups.get(1).getProcName(), MockPolicyData.mockPolicies.get(1));
                      }
                    });
                  }
                });
              }
            });
        put(Arrays.asList("", ""), new HashMap<>());
        put(Arrays.asList(null, null), new HashMap<>());
      }
    };
    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> {

      //TESTS
      for (Map.Entry<List<String>, Map<Object, Object>> testPair : testPairs.entrySet()) {
        Map<String, Map<String, Map<String, PolicyDetails>>> got = policyStore.getUserPolicies(testPair.getKey().get(0), testPair.getKey().get(1));
        context.assertTrue(got.equals(testPair.getValue()));
      }
      async.complete();
    });
  }

  @Test
  public void testGetAssociatedPoliciesGivenAppIdClusterIdProcess(TestContext context) throws Exception {
    final Async async = context.async();
    // PRE
    CompletableFuture f1 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(2), MockPolicyData.mockPolicies.get(2));

    Map<List<String>, Map<Object, Object>> testPairs = new HashMap<List<String>, Map<Object, Object>>() {
      {
        put(Arrays.asList(MockPolicyData.mockProcessGroups.get(0).getAppId(), MockPolicyData.mockProcessGroups.get(0).getCluster(), MockPolicyData.mockProcessGroups.get(0).getProcName()),
            new HashMap<Object, Object>() {
              {
                put(MockPolicyData.mockProcessGroups.get(0).getAppId(), new HashMap<Object, Object>() {
                  {
                    put(MockPolicyData.mockProcessGroups.get(0).getCluster(), new HashMap<Object, Object>() {
                      {
                        put(MockPolicyData.mockProcessGroups.get(0).getProcName(), MockPolicyData.mockPolicies.get(0));
                      }
                    });
                  }
                });
              }
            });
        put(Arrays.asList("", "", ""), new HashMap<>());
        put(Arrays.asList(null, null, null), new HashMap<>());
      }
    };
    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> {

      //TESTS
      for (Map.Entry<List<String>, Map<Object, Object>> testPair : testPairs.entrySet()) {
        Map<String, Map<String, Map<String, PolicyDetails>>> got = policyStore.getUserPolicies(testPair.getKey().get(0), testPair.getKey().get(1), testPair.getKey().get(2));
        context.assertTrue(got.equals(testPair.getValue()));
      }
      async.complete();
    });
  }

  @Test(timeout = 4000)
  public void testGetAssociatedPolicy(TestContext context) throws Exception {
    final Async async = context.async();

    //PRE
    policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0)).whenComplete((aVoid, throwable) -> {
      if (throwable == null) {
        //TEST
        context.assertTrue(policyStore.getUserPolicy(MockPolicyData.mockProcessGroups.get(0)) == MockPolicyData.mockPolicies.get(0));
      }
      async.complete();
    });
  }

  @Test(timeout = 4000)
  public void testSetPolicy(TestContext context) throws Exception {
    final Async async = context.async();

    CompletableFuture<Void> f1 = new CompletableFuture<>();
    CompletableFuture<Void> f2 = new CompletableFuture<>();

    //TEST
    policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0)).whenComplete((aVoid, throwable) -> {
      context.assertTrue(throwable == null);
      f1.complete(null);
    });

    //TEST
    policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), null).whenComplete((aVoid, throwable) -> {
      context.assertTrue(throwable != null);
      f2.complete(null);
    });
    CompletableFuture.allOf(f1, f2).whenComplete((aVoid, throwable) -> async.complete());
  }

  @Test(timeout = 4000)
  public void testRemovePolicy(TestContext context) throws Exception {
    final Async async = context.async();

    CompletableFuture<Void> f1 = new CompletableFuture<>();
    CompletableFuture<Void> f2 = new CompletableFuture<>();
    CompletableFuture<Void> f3 = new CompletableFuture<>();
    //PRE
    policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0)).whenComplete((aVoid, throwable) -> {
      if (throwable == null) {
        //TEST
        policyStore.removeUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0).getAdministrator()).whenComplete((aVoid1, throwable1) -> {
          context.assertTrue(throwable1 == null);
          context.assertTrue(policyStore.getUserPolicy(MockPolicyData.mockProcessGroups.get(0)) == null);
          f1.complete(null);

          //PRE2
          policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0)).whenComplete((aVoid2, throwable2) -> {
            if (throwable2 == null) {
              //TEST2
              policyStore.removeUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(2).getAdministrator()).whenComplete((aVoid3, throwable3) -> {
                context.assertFalse(throwable3 == null);
                context.assertFalse(policyStore.getUserPolicy(MockPolicyData.mockProcessGroups.get(0)) == null);
                f2.complete(null);

                //TEST3
                policyStore.removeUserPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.mockPolicies.get(1).getAdministrator()).whenComplete((aVoid5, throwable5) -> {
                  context.assertFalse(throwable5 == null);
                  context.assertFalse(policyStore.getUserPolicy(MockPolicyData.mockProcessGroups.get(0)) == null);
                  f3.complete(null);
                });
              });
            } else {
              f2.complete(null);
            }
          });
        });
      } else {
        f1.complete(null);
      }
    });

    CompletableFuture.allOf(f1, f2, f3).whenComplete((aVoid, throwable) -> async.complete());
  }

  @Test(timeout = 4000)
  public void testPopulateCacheFromZK(TestContext context) throws Exception {
    final Async async = context.async();

    // PRE
    CompletableFuture f1 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockPolicies.get(0));
    CompletableFuture f2 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.mockPolicies.get(1));
    CompletableFuture f3 = policyStore.setUserPolicy(MockPolicyData.mockProcessGroups.get(2), MockPolicyData.mockPolicies.get(2));

    CompletableFuture.allOf(f1, f2, f3).whenCompleteAsync((aVoid, throwable) -> {
      ZKWithCacheBasedPolicyStore anotherPolicyStore = new ZKWithCacheBasedPolicyStore(curatorClient, policyPath);
      anotherPolicyStore.init();
      //TESTS
      try {
        Thread.sleep(2000);   //Waiting for the cache to get populated
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      int processGroupNum = 0;
      for (PolicyDetails expectedPolicy : MockPolicyData.mockPolicies) {
        context.assertTrue(anotherPolicyStore.getUserPolicy(MockPolicyData.mockProcessGroups.get(processGroupNum++)).equals(expectedPolicy));
      }
      async.complete();
    });
  }
}