import {
  AGGREGATED_PROFILE_DATA_REQUEST,
  AGGREGATED_PROFILE_DATA_SUCCESS,
  AGGREGATED_PROFILE_DATA_FAILURE,
} from 'actions/AggregatedProfileDataActions';

import FrameNode from '../pojos/FrameNode';


function createTree (input, methodLookup, terminalNodeIndexes = []) {
  methodLookup = methodLookup.map(methodName => {
    const splits =  methodName.split(" ");
    if(splits.length === 2){
      return splits;
    }else{
      return [methodName, ""];
    }
  });

  const allNodes = [];
  function formTree (index) {
    let currentNode = input[index];
    if (!currentNode) return {};
    currentNode = new FrameNode(currentNode[0], currentNode[1], currentNode[2], currentNode[3][0], currentNode[3][1]);
    const currentNodeIndex = allNodes.push(currentNode) - 1;
    if (currentNode.childCount !== 0) {
      for (let i = 0; i < currentNode.childCount; i++) {
        const returnValue = formTree(allNodes.length);
        if (returnValue && returnValue.index !== undefined) {
          const childIndex = returnValue.index;
          allNodes[childIndex].parent = currentNodeIndex;
          currentNode.children.push(childIndex);
        }
      }
    }
    if (currentNode.onCPU > 0) terminalNodeIndexes.push(currentNodeIndex);
    return { index: currentNodeIndex };
  }
  return {
    treeRoot: allNodes[formTree(0).index],
    allNodes,
    methodLookup,
    terminalNodeIndexes: terminalNodeIndexes,
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
