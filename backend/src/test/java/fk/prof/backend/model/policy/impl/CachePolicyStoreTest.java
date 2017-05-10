package fk.prof.backend.model.policy.impl;

import fk.prof.backend.MockData;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import policy.PolicyDetails;
import recording.Recorder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test for CachePolicyStoreTest
 * Created by rohit.patiyal on 10/05/17.
 */

public class CachePolicyStoreTest {
    private CachePolicyStore cachePolicyStore;

    @Before
    public void setUp() throws Exception {
        cachePolicyStore = new CachePolicyStore();
    }

    private void populateCachePolicyStore() {
        cachePolicyStore.put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
        cachePolicyStore.put(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
        cachePolicyStore.put(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));
    }

    @Test
    public void testGetGivenAppId() throws Exception {
        //PRE
        populateCachePolicyStore();


        Map<String, Map<Recorder.ProcessGroup, PolicyDetails>> testPairs = new HashMap<String, Map<Recorder.ProcessGroup, PolicyDetails>>() {{
            put(MockData.mockProcessGroups.get(0).getAppId(), new HashMap<Recorder.ProcessGroup, PolicyDetails>() {{
                put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
                put(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
                put(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));
            }});
            put(MockData.mockProcessGroups.get(3).getAppId(), new HashMap<>());
        }};
        //TEST
        for (String appId : testPairs.keySet()) {
            Map<Recorder.ProcessGroup, PolicyDetails> got = cachePolicyStore.get(appId);
            Assert.assertEquals(got, testPairs.get(appId));
        }
    }

    @Test
    public void testGetGivenAppIdClusterId() throws Exception {
        //PRE
        populateCachePolicyStore();
        Map<List<String>, Map<Recorder.ProcessGroup, PolicyDetails>> testPairs = new HashMap<List<String>, Map<Recorder.ProcessGroup, PolicyDetails>>() {{
            put(Arrays.asList(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(0).getCluster()), new HashMap<Recorder.ProcessGroup, PolicyDetails>() {{
                put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
                put(MockData.mockProcessGroups.get(1), MockData.mockPolicies.get(1));
            }});
            put(Arrays.asList(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(2).getCluster()), new HashMap<Recorder.ProcessGroup, PolicyDetails>() {{
                put(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));
            }});
            put(Arrays.asList(MockData.mockProcessGroups.get(3).getAppId(), MockData.mockProcessGroups.get(3).getCluster()), new HashMap<>());
        }};
        //TEST
        for (List<String> params : testPairs.keySet()) {
            Map<Recorder.ProcessGroup, PolicyDetails> got = cachePolicyStore.get(params.get(0), params.get(1));
            Assert.assertEquals(got, testPairs.get(params));
        }
    }

    @Test
    public void testGetGivenAppIdClusterIdProcess() throws Exception {
        //PRE
        populateCachePolicyStore();
        Map<List<String>, Map<Recorder.ProcessGroup, PolicyDetails>> testPairs = new HashMap<List<String>, Map<Recorder.ProcessGroup, PolicyDetails>>() {{
            put(Arrays.asList(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(0).getCluster(), MockData.mockProcessGroups.get(0).getProcName()), new HashMap<Recorder.ProcessGroup, PolicyDetails>() {{
                put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(0));
            }});
            put(Arrays.asList(MockData.mockProcessGroups.get(2).getAppId(), MockData.mockProcessGroups.get(2).getCluster(), MockData.mockProcessGroups.get(2).getProcName()), new HashMap<Recorder.ProcessGroup, PolicyDetails>() {{
                put(MockData.mockProcessGroups.get(2), MockData.mockPolicies.get(2));
            }});
            put(Arrays.asList(MockData.mockProcessGroups.get(3).getAppId(), MockData.mockProcessGroups.get(3).getCluster(), MockData.mockProcessGroups.get(3).getProcName()), new HashMap<>());
        }};
        //TEST
        for (List<String> params : testPairs.keySet()) {
            Map<Recorder.ProcessGroup, PolicyDetails> got = cachePolicyStore.get(params.get(0), params.get(1), params.get(2));
            Assert.assertEquals(got, testPairs.get(params));
        }
    }

    @Test
    public void put() throws Exception {
        //PRE
        populateCachePolicyStore();

        //TEST
        //Putting a new policy for an existing process group
        PolicyDetails oldPolicy = cachePolicyStore.get(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(0).getCluster(), MockData.mockProcessGroups.get(0).getProcName()).get(MockData.mockProcessGroups.get(0));
        Assert.assertEquals(MockData.mockPolicies.get(0), oldPolicy);
        cachePolicyStore.put(MockData.mockProcessGroups.get(0), MockData.mockPolicies.get(1));
        PolicyDetails newPolicy = cachePolicyStore.get(MockData.mockProcessGroups.get(0).getAppId(), MockData.mockProcessGroups.get(0).getCluster(), MockData.mockProcessGroups.get(0).getProcName()).get(MockData.mockProcessGroups.get(0));
        Assert.assertEquals(MockData.mockPolicies.get(1), newPolicy);

        //Putting a new policy for a new process group
        oldPolicy = cachePolicyStore.get(MockData.mockProcessGroups.get(3).getAppId(), MockData.mockProcessGroups.get(3).getCluster(), MockData.mockProcessGroups.get(3).getProcName()).get(MockData.mockProcessGroups.get(3));
        Assert.assertNull(oldPolicy);
        cachePolicyStore.put(MockData.mockProcessGroups.get(3), MockData.mockPolicies.get(2));
        newPolicy = cachePolicyStore.get(MockData.mockProcessGroups.get(3).getAppId(), MockData.mockProcessGroups.get(3).getCluster(), MockData.mockProcessGroups.get(3).getProcName()).get(MockData.mockProcessGroups.get(3));
        Assert.assertEquals(MockData.mockPolicies.get(2), newPolicy);
    }

}