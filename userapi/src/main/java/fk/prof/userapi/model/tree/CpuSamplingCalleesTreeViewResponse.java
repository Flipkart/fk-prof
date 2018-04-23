package fk.prof.userapi.model.tree;

import fk.prof.aggregation.proto.AggregatedProfileModel;

import java.util.List;
import java.util.Map;

public class CpuSamplingCalleesTreeViewResponse extends TreeViewResponse<AggregatedProfileModel.FrameNode> {

  public CpuSamplingCalleesTreeViewResponse(List<IndexedTreeNode<AggregatedProfileModel.FrameNode>> tree,
                                            Map<Integer, String> methodLookup) {
    super(tree, methodLookup);
  }

  @Override
  public List<IndexedTreeNode<AggregatedProfileModel.FrameNode>> getTree() {
    return super.getTree();
  }

}
