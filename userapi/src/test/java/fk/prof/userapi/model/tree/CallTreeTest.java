package fk.prof.userapi.model.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.storage.AsyncStorage;
import fk.prof.userapi.api.StorageBackedProfileLoader;
import fk.prof.userapi.api.MockAggregationWindow;
import fk.prof.userapi.model.AggregatedOnCpuSamples;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.model.json.CustomSerializers;
import fk.prof.userapi.model.json.ProtoSerializers;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Created by gaurav.ashok on 06/06/17.
 */
public class CallTreeTest {

    private static final String sampleStackTraces = "[\n" +
        "  [\"A()\",\"D()\",\"G()\"],\n" +
        "  [\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],\n" +
        "  [\"A()\", \"B()\", \"C()\"],[\"A()\", \"B()\", \"C()\"],\n" +
        "  [\"A()\",\"B()\"],[\"A()\",\"B()\"],[\"A()\",\"B()\"],[\"A()\",\"B()\"],[\"A()\",\"B()\"],\n" +
        "  [\"E()\", \"F()\", \"B()\", \"C()\"],[\"E()\", \"F()\", \"B()\", \"C()\"],[\"E()\", \"F()\", \"B()\", \"C()\"],\n" +
        "  [\"E()\", \"F()\", \"B()\"],[\"E()\", \"F()\", \"B()\"],\n" +
        "  [\"E()\", \"F()\", \"D()\"],[\"E()\", \"F()\", \"D()\"],[\"E()\", \"F()\", \"D()\"],[\"E()\", \"F()\", \"D()\"]\n" +
        "]";

    private static CallTree calltree;
    private static List<String> methodIdLookup;
    private static IndexedTreeNode<FrameNode> expectedTree;
    private static List<IndexedTreeNode<FrameNode>> expectedHotMethodsTree;
    private static ObjectMapper mapper;

    @BeforeClass
    public static void setup() throws Exception {
        AsyncStorage asyncStorage = new MockAggregationWindow.HashMapBasedStorage();
        AggregationWindowStorage storage = MockAggregationWindow.buildMockAggregationWindowStorage(asyncStorage);

        String startime = "2017-03-01T07:00:00";
        ZonedDateTime startimeZ = ZonedDateTime.parse(startime + "Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);
        FinalizedAggregationWindow window = MockAggregationWindow.buildAggregationWindow(startime, () -> sampleStackTraces, 1800);

        // serialize and store
        storage.store(window);

        // init loader
        StorageBackedProfileLoader loader = new StorageBackedProfileLoader(asyncStorage);

        AggregatedProfileNamingStrategy file1 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, 1800, AggregatedProfileModel.WorkType.cpu_sample_work);

        // get cpu sampling data
        AggregatedSamplesPerTraceCtx fullTraceDetail = loader.load(file1).getAggregatedSamples("full-app-trace");
        methodIdLookup = fullTraceDetail.getMethodLookup();

        AggregatedOnCpuSamples cpusampleData = (AggregatedOnCpuSamples) fullTraceDetail.getAggregatedSamples();
        calltree = cpusampleData.getCallTree();

        // expected call tree
        expectedTree =
            node(0, 0, 0, 23,
                node(1, 1, 0, 0),
                node(2, mId("A()"), 0, 14,
                    node(3, mId("D()"), 6, 7,
                        node(4, mId("G()"), 1, 1)),
                    node(5, mId("B()"), 5, 7,
                        node(6, mId("C()"), 2, 2))),
                node(7, mId("E()"), 0, 9,
                    node(8, mId("F()"), 0, 9,
                        node(9, mId("B()"), 2, 5,
                            node(10, mId("C()"), 3, 3)),
                        node(11, mId("D()"), 4, 4))));

        // expected hotMethodTree
        expectedHotMethodsTree =
            Arrays.asList(
                hmnode(4, mId("G()"), 1, 1, 0,
                    hmnode(3, mId("D()"), 6, 7, 1,
                        hmnode(2, mId("A()"), 0, 14, 2,
                            hmnode(0, 0, 0, 23, 3)))),
                hmnode(3, mId("D()"), 6, 7, 1,
                    hmnode(2, mId("A()"), 0, 14, 2,
                        hmnode(0, 0, 0, 23, 3))),
                hmnode(6, mId("C()"), 2, 2, 0,
                    hmnode(5, mId("B()"), 5, 7, 1,
                        hmnode(2, mId("A()"), 0, 14, 2,
                            hmnode(0, 0, 0, 0, 3)))),
                hmnode(5, mId("B()"), 5, 7, 1,
                    hmnode(2, mId("A()"), 0, 14, 2,
                        hmnode(0, 0, 0, 0, 3))),
                hmnode(10, mId("C()"), 3, 3, 0,
                    hmnode(9, mId("B()"), 2, 5, 1,
                        hmnode(8, mId("F()"), 0, 9, 2,
                            hmnode(7, mId("E()"), 0, 9, 1,
                                hmnode(0, 0, 0, 0, 3))))),
                hmnode(9, mId("B()"), 2, 5, 1,
                    hmnode(8, mId("F()"), 0, 9, 2,
                        hmnode(7, mId("E()"), 0, 9, 1,
                            hmnode(0, 0, 0, 0, 3)))),
                hmnode(11, mId("D()"), 4, 4, 0,
                    hmnode(8, mId("F()"), 0, 9, 2,
                        hmnode(7, mId("E()"), 0, 9, 1,
                            hmnode(0, 0, 0, 0, 3)))));

        mapper = new ObjectMapper();
        ProtoSerializers.registerSerializers(mapper);
        CustomSerializers.registerSerializers(mapper);
    }

    @Test
    public void testCallTreeIsCorrect() {
        testTreeEquality(expectedTree, calltree);
    }

    @Test
    public void testCallTreeView() {
        CallTreeView ctv = new CallTreeView(calltree);
        /* root node */
        testTreeEquality(ctv.getSubTrees(ctv.getRoots(), 1, false), toList(expectedTree));
        /* E -> F -> B
                  -> D
         */
        List<IndexedTreeNode<FrameNode>> e_subtree = ctv.getSubTrees(toList(7), 2, false);
        testTreeEquality(e_subtree, toList(expectedTree.getChildren().get(2)));

        /* A -> B -> C
             -> D -> G
         */
        List<IndexedTreeNode<FrameNode>> a_subtree = ctv.getSubTrees(toList(2), 10, false);
        testTreeEquality(a_subtree, toList(expectedTree.getChildren().get(1)));

        /* full tree */
        List<IndexedTreeNode<FrameNode>> fulltree = ctv.getSubTrees(toList(0), 10, false);
        testTreeEquality(fulltree, toList(expectedTree));
    }

    @Test
    public void testSearchFilteredCallTreeView() {
        // subtree that contains B() in its branches.
        int b_id = mId("B()");
        List<Integer> hiddenNodes = Arrays.asList(1, 3, 11);

        FilteredTree<FrameNode> mt = new FilteredTree<>(calltree, calltree.getHotMethodNodeIds(), (i, fn) -> fn.getMethodId() == b_id);
        CallTreeView ctv = new CallTreeView(mt);

        /* root node */
        testTreeEquality(ctv.getSubTrees(ctv.getRoots(), 1, false), filterTree(toList(expectedTree), hiddenNodes));

        /* E -> F -> B
           Branch with D() [id:11] will not be visible
         */
        List<IndexedTreeNode<FrameNode>> e_subtree = ctv.getSubTrees(toList(7), 2, false);
        testTreeEquality(e_subtree,
            filterTree(toList(expectedTree.getChildren().get(2)), hiddenNodes));

        /* full tree
           Branch under nodes 1, 3, 11 will not be visible
         */
        List<IndexedTreeNode<FrameNode>> fulltree = ctv.getSubTrees(toList(0), 10, false);
        testTreeEquality(fulltree,
            filterTree(toList(expectedTree), hiddenNodes));
    }

    @Test
    public void testHotMethodView() {
        CalleesTreeView hmtv = new CalleesTreeView(calltree, calltree.getHotMethodNodeIds());

        // all hotmethods
        List<IndexedTreeNode<FrameNode>> hm = hmtv.getRoots().stream().map(idx -> new IndexedTreeNode<>(idx,calltree.getNode(idx))).collect(Collectors.toList());
        testTreeEquality(hm, expectedHotMethodsTree);

        // get callers of C() [sampleCount:3]
        List<IndexedTreeNode<FrameNode>> C3_callers_2deep = hmtv.getSubTrees(toList(10), 2, false);
        testTreeEquality(C3_callers_2deep, toList(expectedHotMethodsTree.get(4)));

        // get callers of D() [sampleCount:6]
        List<IndexedTreeNode<FrameNode>> D6_callers_1deep = hmtv.getSubTrees(toList(3), 1, false);
        testTreeEquality(D6_callers_1deep, toList(expectedHotMethodsTree.get(1)));
    }

    @Test
    public void testSearchFilteredHotMethodView() {
        // subtree that contains D() in its branches.
        int d_id = mId("D()");

        FilteredTree<FrameNode> mt = new FilteredTree<>(calltree, calltree.getHotMethodNodeIds(), (i, fn) -> fn.getMethodId() == d_id);
        CalleesTreeView hmtv = new CalleesTreeView(mt, mt.getHotMethodNodeIds());

        /* all hotmethods
           only those branches will be visible which has D in it, i.e. 0, 1, 6th element from expectedHotMethodTree
         */
        List<IndexedTreeNode<FrameNode>> hm = hmtv.getRoots().stream().map(idx -> new IndexedTreeNode<>(idx,calltree.getNode(idx))).collect(Collectors.toList());
        testTreeEquality(hm,
            Arrays.asList(expectedHotMethodsTree.get(0), expectedHotMethodsTree.get(1), expectedHotMethodsTree.get(6)));

        // get callers of G() [sampleCount:1]
        List<IndexedTreeNode<FrameNode>> G1_callers_5deep = hmtv.getSubTrees(toList(4), 5, false);
        testTreeEquality(G1_callers_5deep, toList(expectedHotMethodsTree.get(0)));
    }

    @Test
    public void testIndexedTreeNodeSerialize() throws Exception {
        CallTreeView ctv = new CallTreeView(calltree);
        List<IndexedTreeNode<FrameNode>> subtree = ctv.getSubTrees(ctv.getRoots(), 1, false);

        Assert.assertEquals("{\"method_lookup\":{},\"nodes\":{\"0\":{\"d\":[0,0,23],\"c\":{\"1\":{\"d\":[1,0,0]},\"2\":{\"d\":[2,0,14]},\"7\":{\"d\":[7,0,9]}}}}}",
            mapper.writeValueAsString(new CpuSamplingCallTreeViewResponse(subtree, new HashMap<>())));
    }

    private void testTreeEquality(IndexedTreeNode<FrameNode> node, CallTree callTree) {
        Assert.assertEquals(node.getData(), callTree.getNode(node.getIdx()));
        for(IndexedTreeNode<FrameNode> child: node.getChildren()) {
            testTreeEquality(child, callTree);
        }
    }

    private <T> void testTreeEquality(IndexedTreeNode<T> actual, IndexedTreeNode<T> expected) {
        Assert.assertEquals(expected, actual);
        if(actual.getChildren() != null) {
            testTreeEquality(actual.getChildren(), expected.getChildren());
        }
    }

    private <T> void testTreeEquality(List<IndexedTreeNode<T>> actual, List<IndexedTreeNode<T>> expected) {
        Assert.assertTrue(actual == null || actual.size() == expected.size());
        for(IndexedTreeNode<T> child: actual) {
            testTreeEquality(child, expected.get(expected.indexOf(child)));
        }
    }

    @SafeVarargs
    private static IndexedTreeNode<FrameNode> node(int id, int methodId, int cpuSamples, int stackSamples, IndexedTreeNode<FrameNode>... children) {
        return new IndexedTreeNode<>(id, FrameNode.newBuilder()
            .setChildCount(children == null ? 0 : children.length)
            .setMethodId(methodId)
            .setLineNo(0)
            .setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder()
                .setOnStackSamples(stackSamples)
                .setOnCpuSamples(cpuSamples)
            ).build(), children == null ? null : Arrays.asList(children));
    }

    @SafeVarargs
    private static IndexedTreeNode<FrameNode> hmnode(int id, int methodId, int cpuSamples, int stackSamples, int childCount, IndexedTreeNode<FrameNode>... children) {
        return new IndexedTreeNode<>(id, FrameNode.newBuilder()
            .setChildCount(childCount)
            .setMethodId(methodId)
            .setLineNo(0)
            .setCpuSamplingProps(AggregatedProfileModel.CPUSamplingNodeProps.newBuilder()
                .setOnStackSamples(stackSamples)
                .setOnCpuSamples(cpuSamples)
            ).build(), children == null ? null : Arrays.asList(children));
    }

    private static IndexedTreeNode<FrameNode> filterTree(IndexedTreeNode<FrameNode> tree, List<Integer> except) {
        if(except.contains(tree.getIdx())) {
            return null;
        }
        return new IndexedTreeNode<>(tree.getIdx(), tree.getData(), filterTree(tree.getChildren(), except));
    }

    private static List<IndexedTreeNode<FrameNode>> filterTree(List<IndexedTreeNode<FrameNode>> nodes, List<Integer> except) {
        return nodes.stream().map(c -> filterTree(c, except)).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private static int mId(String methodName) {
        return methodIdLookup.indexOf(methodName);
    }

    @SafeVarargs
    private static <T> List<T> toList(T... elements) {
        return Arrays.asList(elements);
    }
}
