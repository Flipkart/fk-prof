import {
  AGGREGATED_PROFILE_DATA_REQUEST,
  AGGREGATED_PROFILE_DATA_SUCCESS,
  AGGREGATED_PROFILE_DATA_FAILURE,
} from 'actions/AggregatedProfileDataActions';

function createTree (input, methodLookup, terminalNodes = []) {
  const allNodes = [];
  function formTree (index) {
    let currentNode = input[index];
    if (!currentNode) return {};
    currentNode = {
      childCount: currentNode[1],
      name: currentNode[0],
      onStack: currentNode[3][0],
      onCPU: currentNode[3][1],
      parent: [],
    };
    const currentNodeIndex = allNodes.push(currentNode) - 1;
    let nextChildIndex = currentNodeIndex;
    if (currentNode.childCount !== 0) {
      for (let i = 0; i < currentNode.childCount; i++) {
        if (!currentNode.children) currentNode.children = [];
        const returnValue = formTree(allNodes.length);
        if (returnValue && returnValue.index !== undefined) {
          nextChildIndex = returnValue.index;
          allNodes[returnValue.index].parent.push(currentNodeIndex);
          currentNode.children.push(nextChildIndex);
        }
      }
    }
    if (currentNode.onCPU > 0) terminalNodes.push(currentNode);
    return { index: currentNodeIndex };
  }
  return {
    treeRoot: formTree(0).node,
    allNodes,
    methodLookup,
    terminalNodes: terminalNodes.sort((a, b) => b.onCPU - a.onCPU),
  };
}

export default function (state = {}, action) {
  switch (action.type) {
    case AGGREGATED_PROFILE_DATA_REQUEST:
      return {
        asyncStatus: 'PENDING',
      };

    case AGGREGATED_PROFILE_DATA_SUCCESS: {
      const { aggregated_samples: { frame_nodes }, method_lookup } = action.res;
      const data = createTree(frame_nodes, method_lookup);
      return {
        asyncStatus: 'SUCCESS',
        data,
      };
    }
    case AGGREGATED_PROFILE_DATA_FAILURE:
      return {
        asyncStatus: 'ERROR',
      };

    default: return state;
  }
}
