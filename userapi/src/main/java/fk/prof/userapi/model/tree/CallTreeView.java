package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.model.Tree;

import java.util.*;

/**
 * Created by gaurav.ashok on 05/06/17.
 */
public class CallTreeView implements ProfileView<IndexedTreeNode<FrameNode>> {

    private Tree<FrameNode> tree;

    public CallTreeView(Tree<FrameNode> tree) {
        this.tree = tree;
    }

    public List<IndexedTreeNode<FrameNode>> getRootNodes() {
        // TODO: fix it. assuming 0 is the root index.
        return Collections.singletonList(new IndexedTreeNode<>(0, tree.getNode(0)));
    }

    public List<IndexedTreeNode<FrameNode>> getSubTree(List<Integer> ids, int depth, boolean autoExpand) {
        return new Expander(ids, depth, autoExpand).expand();
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
                    if(autoExpand && childrenCount > 1) {
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
