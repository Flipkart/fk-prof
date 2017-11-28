package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;

import java.util.List;
import java.util.Map;

/**
 * Response class as the response for getCPUSamplingTreeView http api.
 * @see fk.prof.userapi.model.json.CustomSerializers.TreeViewResponseSerializer A custom serializer for this class.
 *
 * Created by gaurav.ashok on 07/08/17.
 */
public class TreeViewResponse<T> {

    private List<IndexedTreeNode<T>> tree;
    private Map<Integer, String> methodLookup;

    public TreeViewResponse(List<IndexedTreeNode<T>> tree, Map<Integer, String> methodLookup) {
        this.tree = tree;
        this.methodLookup = methodLookup;
    }

    public List<IndexedTreeNode<T>> getTree() {
        return tree;
    }

    public Map<Integer, String> getMethodLookup() {
        return methodLookup;
    }

}
