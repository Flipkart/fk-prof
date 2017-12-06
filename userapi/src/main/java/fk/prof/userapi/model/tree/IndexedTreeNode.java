package fk.prof.userapi.model.tree;

import fk.prof.userapi.model.Tree;

import java.util.List;
import java.util.Objects;

/**
 * Represents a indexable node in the tree. This node has an index, accompanying data and the list of children nodes.
 * Created by gaurav.ashok on 01/06/17.
 */
public class IndexedTreeNode<T> {
    public IndexedTreeNode(int idx, T data) {
        this(idx, data, null);
    }

    public IndexedTreeNode(int idx, T data, List<IndexedTreeNode<T>> children) {
        this.idx = idx;
        this.data = data;
        this.children = children;
    }

    private final int idx;

    private final T data;

    private List<IndexedTreeNode<T>> children;

    public int getIdx() {
        return idx;
    }

    public T getData() {
        return data;
    }

    List<IndexedTreeNode<T>> getChildren() {
        return children;
    }

    public int getChildrenCount() {
        return children != null ? children.size() : 0;
    }

    public void visit(Tree.Visitor<IndexedTreeNode<T>> visitor) {
        visitor.preVisit(this);
        visitor.visit(idx, this);
        if(children != null) {
            for(IndexedTreeNode<T> node : children) {
                node.visit(visitor);
            }
        }
        visitor.postVisit(this);
    }

    IndexedTreeNode<T> setChildren(List<IndexedTreeNode<T>> children) {
        this.children = children;
        return this;
    }

    /**
     * Indicates whether some other object is "equal to" this IndexTreeNode object.
     * Only idx and data members are checked for equality and children are ignored.
     * This is to avoid making the method computationally complex.
     * @param o the object to be checked for equality with
     * @return true or false
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        IndexedTreeNode<?> that = (IndexedTreeNode<?>) o;

        if (idx != that.idx) return false;
        return Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = idx;
        result = 31 * result + data.hashCode();
        return result;
    }
}