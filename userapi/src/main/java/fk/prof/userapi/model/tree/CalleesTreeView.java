package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.model.Tree;
import fk.prof.userapi.model.TreeView;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by gaurav.ashok on 01/06/17.
 */
public class CalleesTreeView implements TreeView<IndexedTreeNode<FrameNode>> {

    private Tree<FrameNode> callTree;
    private List<IndexedTreeNode<FrameNode>> hotMethods;

    public CalleesTreeView(Tree<FrameNode> callTree) {
        this.callTree = callTree;
        findOnCpuFrames();
    }

    public List<IndexedTreeNode<FrameNode>> getRootNodes() {
        return hotMethods;
    }

    public List<IndexedTreeNode<FrameNode>> getSubTrees(List<Integer> ids, int maxDepth, boolean autoExpand) {
        return new Expander(ids, maxDepth, autoExpand).expand();
    }

    private void findOnCpuFrames() {
        hotMethods = new ArrayList<>();
        callTree.foreach((i, node) -> {
            int cpuSampleCount = node.getCpuSamplingProps().getOnCpuSamples();
            if(cpuSampleCount > 0) {
                hotMethods.add(new IndexedTreeNode<>(i, node));
            }
        });
    }

    private class Expander {
        List<Integer> ids;
        int maxDepth;
        boolean autoExpand;

        Expander(List<Integer> ids, int maxDepth, boolean autoExpand) {
            this.ids = ids;
            this.maxDepth = maxDepth;
            this.autoExpand = autoExpand;
        }

        List<IndexedTreeNode<FrameNode>> expand() {
            Map<String, List<IndexedTreeNode<FrameNode>>> idxGroupedByMethodIdLineNum = ids.stream()
                .map(e -> new IndexedTreeNode<>(e, callTree.getNode(e)))
                .collect(Collectors.groupingBy((IndexedTreeNode<FrameNode> e) -> String.valueOf(e.getData().getMethodId()) + ":" + String.valueOf(e.getData().getLineNo())));
            return idxGroupedByMethodIdLineNum.values().stream().peek(this::expand).flatMap(List::stream).collect(Collectors.toList());
        }

        void expand(List<IndexedTreeNode<FrameNode>> nodes) {
            // next set of callers.
            List<IndexedTreeNode<FrameNode>> callers = new ArrayList<>(nodes);
            Set<String> methodIdLineNumSet = new HashSet<>();

            for(int d = 0; d < maxDepth; ++d) {
                methodIdLineNumSet.clear();

                for(int i = 0; i < nodes.size(); ++i) {
                    int callerId = callTree.getParent(callers.get(i).getIdx());
                    // if parent node exist, add it as a caller to the current caller, and update the current caller
                    if(callerId > 0) {
                        FrameNode fn = callTree.getNode(callerId);
                        IndexedTreeNode<FrameNode> caller = new IndexedTreeNode<>(callerId, fn);
                        callers.get(i).setChildren(Collections.singletonList(caller));
                        callers.set(i, caller);
                        // add the methodid to set
                        methodIdLineNumSet.add(String.valueOf(fn.getMethodId())+":"+String.valueOf(fn.getLineNo()));
                    }
                }
                // if there are > 1 distinct caller, stop autoExpansion
                if(autoExpand && methodIdLineNumSet.size() > 1) {
                    return;
                }
            }
        }
    }
}