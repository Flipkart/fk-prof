package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.cache.Cacheable;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.TreeView;
import fk.prof.userapi.model.Tree;

import java.util.*;

/**
 * Created by gaurav.ashok on 05/06/17.
 */
public class CallTreeView implements TreeView<IndexedTreeNode<FrameNode>>, Cacheable {

    private Tree<FrameNode> tree;

    public CallTreeView(Tree<FrameNode> tree) {
        this.tree = tree;
    }

    public List<IndexedTreeNode<FrameNode>> getRootNodes() {
        return Collections.singletonList(new IndexedTreeNode<>(0, tree.getNode(0)));
    }

    public List<IndexedTreeNode<FrameNode>> getSubTrees(List<Integer> ids, int maxDepth, boolean forceExpand) {
        return new Expander(ids, maxDepth, forceExpand).expand();
    }

    @Override
    public ProfileViewType getType() {
        return ProfileViewType.CALLERS;
    }

    /**
     * This is a helper class used to contain variables which remain constant in the expand method
     * as field members of this class
     */
    private class Expander {
        final List<Integer> ids;
        final int maxDepth;
        final boolean forceExpand;

        Expander(List<Integer> ids, int maxDepth, boolean forceExpand) {
            this.ids = ids;
            this.maxDepth = maxDepth;
            this.forceExpand = forceExpand;
        }

        List<IndexedTreeNode<FrameNode>> expand() {
            List<IndexedTreeNode<FrameNode>> expansion = new ArrayList<>(ids.size());
            for(Integer id : ids) {
                expansion.add(new IndexedTreeNode<>(id, tree.getNode(id), expand(id, 0)));
            }
            return expansion;
        }

        private List<IndexedTreeNode<FrameNode>> expand(int idx, int curDepth) {
            boolean tooDeep = curDepth >= maxDepth;
            int childrenCount = tree.getChildrenCount(idx);

            if(tooDeep || childrenCount == 0) {
                return null;
            }
            else {
                List<IndexedTreeNode<FrameNode>> children = new ArrayList<>(childrenCount);
                for(Integer i : tree.getChildren(idx)) {
                    // in case of autoExpansion, if childrenSize > 1, dont expand more
                    if (!forceExpand && childrenCount > 1) {
                        children.add(new IndexedTreeNode<>(i, tree.getNode(i)));
                    }
                    else {
                        children.add(new IndexedTreeNode<>(i, tree.getNode(i), expand(i, curDepth + 1)));
                    }
                }
                return children;
            }
        }
    }
}
