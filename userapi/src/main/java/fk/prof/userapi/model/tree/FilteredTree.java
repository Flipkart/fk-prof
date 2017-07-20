package fk.prof.userapi.model.tree;

import fk.prof.userapi.model.Tree;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Created by gaurav.ashok on 05/06/17.
 */
public class FilteredTree<T> implements Tree<T> {

    Tree<T> tree;
    boolean[] visible;

    public FilteredTree(Tree<T> tree, VisibilityPredicate<T> predicate) {
        this.tree = tree;
        this.visible = new boolean[getMaxSize()];

        tree.foreach((i, node) -> visible[i] = predicate.testVisibility(i, node));

        treeify(0, false);
    }

    @Override
    public T get(int idx) {
        if(visible[idx]) {
            return tree.get(idx);
        }
        throw new NoSuchElementException();
    }

    @Override
    public int getChildrenSize(int idx) {
        int childCount = 0;
        for(Integer i : getChildren(idx)) {
            ++childCount;
        }
        return childCount;
    }

    @Override
    public Iterable<Integer> getChildren(int idx) {
        return () -> new Iterator<Integer>() {
            Iterator<Integer> children = tree.getChildren(idx).iterator();
            Integer next;

            @Override
            public boolean hasNext() {
                if(next != null) {
                    return true;
                }

                try {
                    next = getNextChild();
                    return true;
                }
                catch (NoSuchElementException e) {
                    // no next element
                }
                return false;
            }

            @Override
            public Integer next() {
                if(hasNext()) {
                    Integer nextChild = next;
                    next = null;
                    return nextChild;
                }
                throw new NoSuchElementException();
            }

            private Integer getNextChild() {
                while(children.hasNext()) {
                    Integer nextChild = children.next();
                    if(!visible[nextChild]) {
                        continue;
                    }
                    return nextChild;
                }
                throw new NoSuchElementException();
            }
        };
    }

    @Override
    public int getParent(int idx) {
        if(visible[idx]) {
            return tree.getParent(idx);
        }
        throw new NoSuchElementException();
    }

    @Override
    public int getMaxSize() {
        return tree.getMaxSize();
    }

    @Override
    public void foreach(Visitor<T> visitor) {
        tree.foreach((i, node) -> {
            if(visible[i]) {
                visitor.visit(i, node);
            }
        });
    }

    private boolean treeify(int idx, boolean isParentVisible) {
        boolean isNodeVisible = visible[idx] | isParentVisible;
        boolean isChildVisible = false;
        for(Integer i : tree.getChildren(idx)) {
            isChildVisible |= treeify(i, isNodeVisible);
        }
        visible[idx] = isNodeVisible | isChildVisible;
        return visible[idx];
    }

    public interface VisibilityPredicate<T> {
        boolean testVisibility(int idx, T node);
    }
}
