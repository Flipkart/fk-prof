package fk.prof.userapi.model;

import java.util.List;

//TODO: Add javadocs
public interface TreeView<T> {
  List<T> getRootNodes();
  List<T> getSubTrees(List<Integer> ids, int depth, boolean autoExpand);
}
