package fk.prof.userapi.model;

import java.util.List;

/**
 * Interface representing a ProfileView having a form of a tree / multiple trees (forest)
 * @param <T> Type of the node which composes the Tree
 */
public interface TreeView<T> extends ProfileView {
  /**
   * Get the root/s of the tree / forest
   * @return list of root node ids
   */
  List<Integer> getRoots();

  /**
   * Get the subtree roots for each of the provided node ids.
   * @param ids ids of the nodes of which the subtree nodes are to be returned
   * @param maxDepth the maximum depth up to which the subtree can be expanded
   * @param forceExpand whether the tree is to be force expanded up to the maxDepth or
   *                   stopped if a fork is encountered before the maxDepth
   * @return the list of sub tree roots
   */
  List<T> getSubTrees(List<Integer> ids, int maxDepth, boolean forceExpand);
}
