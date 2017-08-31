package fk.prof.backend.model.policy;

import fk.prof.backend.mock.MockPolicyData;
import fk.prof.backend.proto.PolicyDTO;
import fk.prof.backend.util.ZookeeperUtil;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import recording.Recorder;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static fk.prof.backend.util.PathNamingUtil.encode32;
import static fk.prof.backend.util.ZookeeperUtil.DELIMITER;

/**
 * Test for ZKWithCacheBasedPolicyStore
 * Created by rohit.patiyal on 09/03/17.
 */
@RunWith(VertxUnitRunner.class)
public class ZookeeperBasedPolicyStoreAPITest {
    private static final String POLICY_BASEDIR = "policy";
    private static final String POLICY_VERSION = "v0001";
    private TestingServer testingServer;
    private CuratorFramework curatorClient;
    private ZookeeperBasedPolicyStoreAPI policyStoreAPI;
    private Vertx vertx;

    @Before
    public void setUp() throws Exception {
        testingServer = new TestingServer();
        Timing timing = new Timing();
        curatorClient = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        curatorClient.start();
        curatorClient.blockUntilConnected(10, TimeUnit.SECONDS);
        curatorClient.create().forPath(DELIMITER + POLICY_BASEDIR);
        vertx = Vertx.vertx();
        policyStoreAPI = new ZookeeperBasedPolicyStoreAPI(vertx, curatorClient, POLICY_BASEDIR, POLICY_VERSION);
    }

    @After
    public void tearDown(TestContext context) throws Exception {
        final Async async = context.async();
        curatorClient.close();
        testingServer.close();
        vertx.close(event -> {
            if (event.succeeded()) {
                async.complete();
            } else {
                context.fail();
            }
        });
    }

    @Test(timeout = 2000)
    public void testGetPolicy(TestContext context) throws Exception {
        final Async async = context.async();
        //PRE
        policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockVersionedPolicyDetails.get(0)).setHandler(ar -> {
            if (ar.succeeded()) {
                System.out.println("CREATING SUCCEEDED");
                PolicyDTO.VersionedPolicyDetails got = policyStoreAPI.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0));
                //GET GIVES THE JUST CREATED POLICY WITH VERSION BUMP
                context.assertEquals(got.getVersion(), MockPolicyData.mockVersionedPolicyDetails.get(0).getVersion() + 1);
                context.assertEquals(got.getPolicyDetails(), MockPolicyData.mockVersionedPolicyDetails.get(0).getPolicyDetails());
            } else {
                context.fail(ar.cause());
            }
            async.complete();
        });
    }

    @Test(timeout = 2000)
    public void testCreatePolicy(TestContext context) throws Exception {
        final Async async = context.async();
        Recorder.ProcessGroup pG = MockPolicyData.mockProcessGroups.get(0);
        policyStoreAPI.createVersionedPolicy(pG, MockPolicyData.mockVersionedPolicyDetails.get(0)).setHandler(ar -> {
            //SUCCESS ON CREATION OF A NEW POLICY
            context.assertEquals(ar.result().getPolicyDetails(), MockPolicyData.mockVersionedPolicyDetails.get(0).getPolicyDetails());
            context.assertEquals(ar.result().getVersion(), MockPolicyData.mockVersionedPolicyDetails.get(0).getVersion() + 1);

            String procNamePath = DELIMITER + POLICY_BASEDIR + DELIMITER + POLICY_VERSION + DELIMITER + encode32(pG.getAppId()) + DELIMITER + encode32(pG.getCluster()) + DELIMITER + encode32(pG.getProcName());
            try {
                //DATA IS ACTUALLY WRITTEN TO ZK
                context.assertEquals(PolicyDTO.PolicyDetails.parseFrom(ZookeeperUtil.readLatestSeqZNodeChild(curatorClient, procNamePath).getValue()), MockPolicyData.mockVersionedPolicyDetails.get(0).getPolicyDetails());
                int gotVersion = Integer.parseInt(ZookeeperUtil.getLatestSeqZNodeChildName(curatorClient, procNamePath));
                //VERSION OF WRITTEN TO ZK IS ONE PLUS THE OLD VERSION
                context.assertEquals(gotVersion, MockPolicyData.mockVersionedPolicyDetails.get(0).getVersion() + 1);

                //GET GIVES THE CREATED VERSIONED POLICY
                PolicyDTO.VersionedPolicyDetails got = policyStoreAPI.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0));
                context.assertEquals(got.getPolicyDetails(), MockPolicyData.mockVersionedPolicyDetails.get(0).getPolicyDetails());
                context.assertEquals(got.getVersion(), MockPolicyData.mockVersionedPolicyDetails.get(0).getVersion() + 1);

                policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockVersionedPolicyDetails.get(1)).setHandler(ar2 -> {
                    //FAILURE ON RECREATING A POLICY FOR AN EXISTING PROCESS GROUP
                    context.assertTrue(ar2.failed());
                    PolicyDTO.VersionedPolicyDetails got2 = policyStoreAPI.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0));
                    //POLICY IS SAME AS ORIGINAL
                    context.assertEquals(got2.getPolicyDetails(), MockPolicyData.mockVersionedPolicyDetails.get(0).getPolicyDetails());
                    context.assertEquals(got2.getVersion(), MockPolicyData.mockVersionedPolicyDetails.get(0).getVersion() + 1);
                });
            } catch (Exception e) {
                context.fail(e);
            }
            async.complete();
        });
    }

    @Test(timeout = 3000)
    public void testCompetingCreatesSameProcessGroup(TestContext context) {
        final Async async = context.async();
        int nThreads = 500;
        //SAME PROCESSGROUP: ONLY ONE CREATE SHOULD SUCCEED, OTHERS SHOULD FAIL WITH ALREADY EXISTS ERROR
        AtomicInteger successes = new AtomicInteger(0);
        CountDownLatch starterLatch = new CountDownLatch(1);
        CountDownLatch finishBarrierLatch = new CountDownLatch(nThreads);

        for (int t = 0; t < nThreads; t++) {
            int finalT = t;
            new Thread(() -> {
                try {
                    starterLatch.await();
                } catch (InterruptedException e) {
                    context.fail(e);
                }
                policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(finalT % 3), -1))
                        .setHandler(ar -> {
                            if (ar.succeeded()) {
                                successes.incrementAndGet();
                            } else {
                                context.assertTrue(ar.cause().getMessage().contains("already exists"));
                            }
                            finishBarrierLatch.countDown();
                        });
            }).start();
        }
        starterLatch.countDown();

        new Thread(() -> {
            try {
                finishBarrierLatch.await(2, TimeUnit.SECONDS);
                context.assertEquals(successes.get(), 1);
            } catch (InterruptedException e) {
                context.fail(e);
            } finally {
                async.complete();
            }
        }).start();
    }

    @Test(timeout = 3000)
    public void testCompetingCreatesDiffProcessGroup(TestContext context) {
        final Async async = context.async();
        int nThreads = 50;
        int distinctPGs = 10;
        //DIFFERENT PROCESSGROUP: ALL DISTINCT PROCESS GROUPS SHOULD SUCCEED
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failedDueToAlreadyExists = new AtomicInteger(0);
        CountDownLatch starterLatch = new CountDownLatch(1);
        CountDownLatch finishBarrierLatch = new CountDownLatch(nThreads);

        for (int t = 0; t < nThreads; t++) {
            int finalT = t;
            new Thread(() -> {
                try {
                    starterLatch.await();
                } catch (InterruptedException e) {
                    context.fail(e);
                }
                policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0).toBuilder().setProcName("p" + finalT % distinctPGs).build(), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(finalT % 3), -1))
                        .setHandler(ar -> {
                            if (ar.succeeded()) {
                                successes.incrementAndGet();
                            } else {
                                context.assertTrue(ar.cause().getMessage().contains("already exists"));
                                if (ar.cause().getMessage().contains("already exists")) {
                                    failedDueToAlreadyExists.incrementAndGet();
                                }
                            }
                            finishBarrierLatch.countDown();
                        });
            }).start();
        }
        starterLatch.countDown();

        new Thread(() -> {
            try {
                finishBarrierLatch.await(2, TimeUnit.SECONDS);
                context.assertEquals(successes.get(), distinctPGs);
                context.assertEquals(failedDueToAlreadyExists.get(), nThreads - distinctPGs);
            } catch (InterruptedException e) {
                context.fail(e);
            } finally {
                async.complete();
            }
        }).start();
    }

    @Test(timeout = 2000)
    public void testUpdatePolicy(TestContext context) throws Exception {
        final Async async = context.async();
        Future<Void> future1 = Future.future();
        Future<Void> future2 = Future.future();

        //USING PG1
        policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockVersionedPolicyDetails.get(0)).setHandler(ar -> {
            if (ar.succeeded()) {
                policyStoreAPI.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockVersionedPolicyDetails.get(1)).setHandler(ar2 -> {
                    //SUCCESS ON UPDATING AN EXISTING POLICY
                    context.assertTrue(ar2.succeeded());
                    //POLICY IS THE NEW ONE
                    context.assertEquals(ar2.result().getVersion(), MockPolicyData.mockVersionedPolicyDetails.get(1).getVersion() + 1);
                    context.assertEquals(ar2.result().getPolicyDetails(), MockPolicyData.mockVersionedPolicyDetails.get(1).getPolicyDetails());


                    PolicyDTO.VersionedPolicyDetails got2 = policyStoreAPI.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0));
                    //GET GIVES THE NEW POLICY
                    context.assertEquals(got2.getVersion(), MockPolicyData.mockVersionedPolicyDetails.get(1).getVersion() + 1);
                    context.assertEquals(got2.getPolicyDetails(), MockPolicyData.mockVersionedPolicyDetails.get(1).getPolicyDetails());
                    future1.complete();
                });
            } else {
                future1.fail(ar.cause());
                context.fail(ar.cause());
            }
        });

        //USING PG2
        policyStoreAPI.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.mockVersionedPolicyDetails.get(0)).setHandler(ar -> {
            //FAILURE ON UPDATING A NON EXISTING POLICY
            context.assertTrue(ar.failed());
            PolicyDTO.VersionedPolicyDetails got2 = policyStoreAPI.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(1));
            //POLICY IS STILL NULL
            context.assertNull(got2);
            future2.complete();
        });

        CompositeFuture.all(future1, future2).setHandler(event -> async.complete());
    }

    @Test(timeout = 4000)
    public void testCompetingUpdatesDiffProcessGroup(TestContext context) {
        final Async async = context.async();
        int nThreads = 3;

        policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.mockVersionedPolicyDetails.get(0)).setHandler(ar -> {
                    //ONE PROCESSGROUP: ONE UPDATE ON CURRENT VERSION SHOULD SUCCEED, OTHERS SHOULD FAIL WITH VERSION MISMATCH
                    AtomicInteger successes = new AtomicInteger(0);
                    AtomicInteger failedDueToAlreadyExists = new AtomicInteger(0);
                    CountDownLatch starterLatch = new CountDownLatch(1);
                    CountDownLatch finishBarrierLatch = new CountDownLatch(nThreads);

                    for (int t = 0; t < nThreads; t++) {
                        int finalT = t;
                        new Thread(() -> {
                            try {
                                starterLatch.await();
                            } catch (InterruptedException e) {
                                context.fail(e);
                            }
                            policyStoreAPI.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(finalT % 2), 0))
                                    .setHandler(ar2 -> {
                                        if (ar2.succeeded()) {
                                            successes.incrementAndGet();
                                        } else {
                                            context.assertTrue(ar2.cause().getMessage().contains("mismatch"));
                                            if (ar2.cause().getMessage().contains("mismatch")) {
                                                failedDueToAlreadyExists.incrementAndGet();
                                            }
                                        }
                                        finishBarrierLatch.countDown();
                                    });
                        }).start();
                    }
                    starterLatch.countDown();
                    new Thread(() -> {
                        try {
                            finishBarrierLatch.await(2, TimeUnit.SECONDS);
                            context.assertEquals(successes.get(), 1);
                            context.assertEquals(failedDueToAlreadyExists.get(), nThreads - 1);
                        } catch (InterruptedException e) {
                            context.fail(e);
                        } finally {
                            async.complete();
                        }
                    }).start();
                }
        );
    }

    @Test(timeout = 8000)
    public void testInit(TestContext context) throws Exception {
        final Async async = context.async();

        Future<PolicyDTO.VersionedPolicyDetails> future1 = policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(0), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(0), -1));
        Future<PolicyDTO.VersionedPolicyDetails> future2 = Future.future();
        policyStoreAPI.createVersionedPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(1), -1)).setHandler(ar -> {
            if (ar.succeeded()) {
                policyStoreAPI.updateVersionedPolicy(MockPolicyData.mockProcessGroups.get(1), MockPolicyData.getMockVersionedPolicyDetails(MockPolicyData.mockPolicyDetails.get(2), 0)).setHandler(ar2 -> {
                    if (ar2.succeeded()) {
                        future2.complete();
                    } else {
                        future2.fail(ar2.cause());
                    }
                });
            } else {
                future2.fail(ar.cause());
            }
        });
        CompositeFuture.all(future1, future2).setHandler(event -> {
            ZookeeperBasedPolicyStoreAPI anotherPolicyStore = new ZookeeperBasedPolicyStoreAPI(vertx, curatorClient, POLICY_BASEDIR, POLICY_VERSION);
            try {
                //GET RETURNS NULL RESULTS FROM ANOTHERSTORE BEFORE INIT
                PolicyDTO.VersionedPolicyDetails got1 = anotherPolicyStore.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0));
                PolicyDTO.VersionedPolicyDetails got2 = anotherPolicyStore.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(1));
                context.assertNull(got1);
                context.assertNull(got2);

                anotherPolicyStore.init();
                //GET RETURNS SAME RESULTS AS ORIGINAL STORE FROM ANOTHER STORE AFTER INIT
                got1 = anotherPolicyStore.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(0));
                got2 = anotherPolicyStore.getVersionedPolicy(MockPolicyData.mockProcessGroups.get(1));
                context.assertEquals(got1.getVersion(), 0);
                context.assertEquals(got2.getVersion(), 1);
            } catch (Exception e) {
                context.fail(e);
            }
            async.complete();
        });
    }

    @Test(timeout = 2000)
    public void testGetAppIds(TestContext context) {
        final Async async = context.async();
        List<Future> futures = new ArrayList<>();
        for (Recorder.ProcessGroup pG : MockPolicyData.mockProcessGroups) {
            futures.add(policyStoreAPI.createVersionedPolicy(pG, MockPolicyData.mockVersionedPolicyDetails.get(0)));
        }
        Map<String, Set<String>> testPairs = new HashMap<String, Set<String>>() {{
            put("a", new HashSet<>(Arrays.asList("a1", "a2")));
            put("", new HashSet<>(Arrays.asList("a1", "a2", "b1")));
            put(null, null);
        }};
        CompositeFuture.all(futures).setHandler(event -> {
            if (event.succeeded()) {
                for (String pre : testPairs.keySet()) {
                    try {
                        Set<String> got = policyStoreAPI.getAppIds(pre);
                        context.assertEquals(got, testPairs.get(pre));
                    } catch (NullPointerException ex) {
                        //Expected exception
                        if (testPairs.get(pre) != null) {
                            context.fail("Unexpected NPE");
                        }
                    } catch (Exception ex) {
                        context.fail("Unexpected exception thrown");
                    }

                }

            } else {
                context.fail(event.cause());
            }
            async.complete();
        });

    }

    @Test(timeout = 2000)
    public void testGetClusterIds(TestContext context) {
        final Async async = context.async();
        List<Future> futures = new ArrayList<>();
        for (Recorder.ProcessGroup pG : MockPolicyData.mockProcessGroups) {
            futures.add(policyStoreAPI.createVersionedPolicy(pG, MockPolicyData.mockVersionedPolicyDetails.get(1)));
        }
        Map<List<String>, Set<String>> testPairs = new HashMap<List<String>, Set<String>>() {{
            put(Arrays.asList("a1", "c1"), new HashSet<>(Arrays.asList("c1")));
            put(Arrays.asList("a1", ""), new HashSet<>(Arrays.asList("c1", "c2")));
            put(Arrays.asList("", ""), null);
            put(Arrays.asList("a2", null), null);
            put(Arrays.asList(null, "c1"), null);
        }};
        CompositeFuture.all(futures).setHandler(event -> {
            if (event.succeeded()) {
                for (List<String> args : testPairs.keySet()) {
                    try {
                        Set<String> got = policyStoreAPI.getClusterIds(args.get(0), args.get(1));
                        context.assertEquals(got, testPairs.get(args));
                    } catch (NullPointerException ex) {
                        //Expected exception
                        if (testPairs.get(args) != null) {
                            context.fail("Unexpected NPE");
                        }
                    } catch (Exception ex) {
                        context.fail("Unexpected exception thrown");
                    }
                }
            } else {
                context.fail(event.cause());
            }
            async.complete();
        });

    }

    @Test(timeout = 2000)
    public void testGetProcNames(TestContext context) {
        final Async async = context.async();
        List<Future> futures = new ArrayList<>();
        for (Recorder.ProcessGroup pG : MockPolicyData.mockProcessGroups) {
            futures.add(policyStoreAPI.createVersionedPolicy(pG, MockPolicyData.mockVersionedPolicyDetails.get(2)));
        }
        Map<List<String>, Set<String>> testPairs = new HashMap<List<String>, Set<String>>() {{
            put(Arrays.asList("a1", "c1", "p1"), new HashSet<>(Arrays.asList("p1")));
            put(Arrays.asList("a1", "c1", ""), new HashSet<>(Arrays.asList("p1", "p2")));
            put(Arrays.asList("", "c1", ""), null);
            put(Arrays.asList("", "c1", null), null);
            put(Arrays.asList(null, "c1", "p1"), null);
        }};
        CompositeFuture.all(futures).setHandler(event -> {
            if (event.succeeded()) {
                for (List<String> args : testPairs.keySet()) {
                    try {
                        Set<String> got = policyStoreAPI.getProcNames(args.get(0), args.get(1), args.get(2));
                        context.assertEquals(got, testPairs.get(args));
                    } catch (NullPointerException ex) {
                        //Expected exception
                        if (testPairs.get(args) != null) {
                            context.fail("Unexpected NPE");
                        }
                    } catch (Exception ex) {
                        context.fail("Unexpected exception thrown");
                    }
                }
            } else {
                context.fail(event.cause());
            }
            async.complete();
        });
    }

}