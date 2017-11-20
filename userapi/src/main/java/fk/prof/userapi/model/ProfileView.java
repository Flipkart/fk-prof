package fk.prof.userapi.model;

import java.util.List;

//TODO: Add javadocs
public interface ProfileView<T> {
  List<T> getRootNodes();
  List<T> getSubTree(List<Integer> ids, int depth, boolean autoExpand);
}
