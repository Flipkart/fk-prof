package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.model.Tree;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Created by gaurav.ashok on 01/06/17.
 */
public class CallTree implements Tree<FrameNode>{

    private List<FrameNode> nodes;
    private int[] subtreeSizes;
    private int[] parentIds;

    /**
     * Constructor for creating a CallTree from a list of FrameNodes orders in a dfs manner. i.e.
     * first node should be the root node and then followed by its subtree, with the subtree ordered
     * in the same way recursively.
     * @param frameNodes the list of frameNodes
     */
    public CallTree(List<FrameNode> frameNodes) {
        this.nodes = frameNodes;
        treeify();
    }

    /**
     * Parses a serialized tree from provided inputStream into a CallTree object. Input should be a dfs serialization
     * of the tree i.e. first node should be the root node and then followed by its subtree, with the subtree serialized
     * using the same definition recursively.
     *
     * @param in the inputStream providing parsable FrameNodes
     * @return the instantiated CallTree object from the FrameNodes provided by the inputStream
     * @throws IOException when inputStream is not parseable
     */
    public static CallTree parseFrom(InputStream in) throws IOException {
        int nodeCount = 1; // for root node
        int parsedNodeCount = 0;
        List<FrameNode> parsedFrameNodes = new ArrayList<>();
        do {
            AggregatedProfileModel.FrameNodeList frameNodeList = AggregatedProfileModel.FrameNodeList.parseDelimitedFrom(in);
            for(FrameNode node: frameNodeList.getFrameNodesList()) {
                nodeCount += node.getChildCount();
            }
            parsedNodeCount += frameNodeList.getFrameNodesCount();
            parsedFrameNodes.addAll(frameNodeList.getFrameNodesList());
        } while(parsedNodeCount < nodeCount && parsedNodeCount > 0);

        return new CallTree(parsedFrameNodes);
    }

    @Override
    public FrameNode getNode(int idx) {
        return nodes.get(idx);
    }

    @Override
    public int getChildrenCount(int idx) {
        return nodes.get(idx).getChildCount();
    }

    @Override
    public Iterable<Integer> getChildren(int idx) {
        return () -> new Iterator<Integer>() {
            private int childCount = getChildrenCount(idx);
            private int childCounter = 0;
            private int offset = 1;
            @Override
            public boolean hasNext() {
                return childCounter < childCount;
            }

            @Override
            public Integer next() {
                if(!hasNext()) {
                    throw new NoSuchElementException();
                }
                ++childCounter;
                int nextChildIdx = idx + offset;
                offset += subtreeSizes[nextChildIdx];
                return nextChildIdx;
            }
        };
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public void foreach(Visitor<FrameNode> visitor) {
        for(int i = 0; i < nodes.size(); ++i) {
            visitor.visit(i, nodes.get(i));
        }
    }

    @Override
    public int getParent(int idx) {
        return parentIds[idx];
    }

    /**
     * Populates the member fields subTreeSizes, parentIds of callTree object using
     * nodes list
     */
    private void treeify() {
        subtreeSizes = new int[nodes.size()];
        parentIds = new int[nodes.size()];

        int treeSize = 0;
        if (nodes.size() > 0) {
            treeSize = buildTree(0,-1);
        }
        if(treeSize != nodes.size()) {
            throw new IllegalStateException("not able to build calltree");
        }
    }

    /**
     * Recursively traverses the tree node list and populates parentIds and subtreeSizes helping the treeify
     * method
     * @param idx the index of the current in the node list
     * @param parentIdx the index of the current node in the node list
     * @return the size of the subtree rooted at index idx including the root itself
     */
    private int buildTree(int idx, int parentIdx) {
        int treeSize = 1;
        AggregatedProfileModel.FrameNode curr = nodes.get(idx);
        parentIds[idx] = parentIdx;
        for(int i = 0; i < curr.getChildCount(); ++i) {
            treeSize += buildTree(idx + treeSize, idx);
        }
        subtreeSizes[idx] = treeSize;
        return treeSize;
    }

}
