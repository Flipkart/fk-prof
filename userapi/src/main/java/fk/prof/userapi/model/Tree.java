package fk.prof.userapi.model;

/**
 * Interface for a tree of type T
 * Created by gaurav.ashok on 01/06/17.
 */
public interface Tree<T> {
    /**
     * Gets the tree node at index idx
     * @param idx index of the tree node
     * @return tree node of type T
     */
    T getNode(int idx);

    /**
     * Returns children count of the tree node at index idx
     * @param idx index of the node of which the children count is to be get
     * @return the number of children
     */
    int getChildrenCount(int idx);

    /**
     * Returns an iterable on children indexes of the node at index idx
     * @param idx index of the node of which the children iterable is to be get
     * @return the iterable on the children indexes
     */
    Iterable<Integer> getChildren(int idx);

    /**
     * Gets the index of the parent of the tree node at index idx
     * @param idx index of the node of which parent is to be get
     * @return  the index of the parent node
     */
    int getParent(int idx);

    /**
     * Returns the size of the tree, i.e. the number of nodes in the tree
     * @return the size
     */
    int size();

    /**
     * Runs the visitor method for each node of the tree
     * @param visitor the method to be run
     */
    void foreach(Visitor<T> visitor);

    /**
     * Functional interface with function signature : (index, node) -> (function implementation returning void)
     * @param <T> type of the tree node
     */
    interface Visitor<T> {
        void    visit(int idx, T node);
    }
}
