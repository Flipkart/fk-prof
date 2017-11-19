package fk.prof.userapi.model;

import fk.prof.userapi.cache.Cacheable;

import java.util.List;

//TODO: Add javadocs
public interface TreeView<T> extends Cacheable<TreeView<T>> {
  List<T> getRootNodes();
  List<T> getSubTree(List<Integer> ids, int depth, boolean autoExpand);
}
