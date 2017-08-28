package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.model.Tree;

import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by gaurav.ashok on 01/06/17.
 */
public class CalleesTreeView implements CacheableView {

    private Tree<FrameNode> callTree;
    private List<IndexedTreeNode<FrameNode>> hotMethods;

    public CalleesTreeView(Tree<FrameNode> callTree) {
        this.callTree = callTree;
        findOnCpuFrames();
    }

    public List<IndexedTreeNode<FrameNode>> getRootNodes() {
        return hotMethods;
    }

    public List<IndexedTreeNode<FrameNode>> getSubTree(List<Integer> ids, int depth, boolean autoExpand) {
        return new Expander(ids, depth, autoExpand).expand();
    }

    private void findOnCpuFrames() {
        hotMethods = new ArrayList<>();
        callTree.foreach((i, fn) -> {
            int cpuSampleCount = fn.getCpuSamplingProps().getOnCpuSamples();
            if(cpuSampleCount > 0) {
                hotMethods.add(new IndexedTreeNode<>(i, fn));
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
            Map<Integer, List<IndexedTreeNode<FrameNode>>> idxGroupedByMethodId = ids.stream()
                .map(e -> new IndexedTreeNode<>(e, callTree.get(e)))
                .collect(Collectors.groupingBy(e -> e.getData().getMethodId()));
            return idxGroupedByMethodId.values().stream().peek(this::expand).flatMap(List::stream).collect(Collectors.toList());
        }

        void expand(List<IndexedTreeNode<FrameNode>> nodes) {
            // next set of callers.
            List<IndexedTreeNode<FrameNode>> callers = new ArrayList<>(nodes);
            HashSet<Integer> methodidSet = new HashSet<>();

            for(int d = 0; d < maxDepth; ++d) {
                methodidSet.clear();

                for(int i = 0; i < nodes.size(); ++i) {
                    int callerId = callTree.getParent(callers.get(i).getIdx());
                    // if parent node exist, add it as a caller to the current caller, and update the current caller
                    if(callerId > 0) {
                        FrameNode fn = callTree.get(callerId);
                        IndexedTreeNode<FrameNode> caller = new IndexedTreeNode<>(callerId, fn);
                        callers.get(i).setChildren(Collections.singletonList(caller));
                        callers.set(i, caller);
                        // add the methodid to set
                        methodidSet.add(fn.getMethodId());
                    }
                }
                // if there are > 1 distinct caller, stop autoExpansion
                if(autoExpand && methodidSet.size() > 1) {
                    return;
                }
            }
        }
    }
}
