package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.cache.Cacheable;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.Tree;
import fk.prof.userapi.model.TreeView;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by gaurav.ashok on 01/06/17.
 */
public class CalleesTreeView implements TreeView<IndexedTreeNode<FrameNode>>, Cacheable {

    private Tree<FrameNode> callTree;
    private List<Integer> hotMethodNodeIds;

    public CalleesTreeView(Tree<FrameNode> callTree, List<Integer> hotMethodNodeIds) {
        this.callTree = callTree;
        this.hotMethodNodeIds = hotMethodNodeIds;
    }

    public List<Integer> getRootIds() {
        return hotMethodNodeIds;
    }

    public List<IndexedTreeNode<FrameNode>> getSubTrees(List<Integer> ids, int maxDepth, boolean forceExpand) {
        return new Expander(ids, maxDepth, forceExpand).expand();
    }

    @Override
    public ProfileViewType getType() {
        return ProfileViewType.CALLEES;
    }

    /**
     * This is a helper class in order to contain variables as field members
     * which will remain constant in the expand method
     */
    private class Expander {
        List<Integer> ids;
        int maxDepth;
        boolean forceExpand;

        Expander(List<Integer> ids, int maxDepth, boolean forceExpand) {
            this.ids = ids;
            this.maxDepth = maxDepth;
            this.forceExpand = forceExpand;
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
                // if there are > 1 distinct caller, stop expansion
                if(!forceExpand && methodIdLineNumSet.size() > 1) {
                    return;
                }
            }
        }
    }
}
