import {
  GET_CPU_SAMPLING_REQUEST,
  GET_CPU_SAMPLING_SUCCESS,
  GET_CPU_SAMPLING_FAILURE,
} from 'actions/CPUSamplingActions';

import findIndex from 'utils/findIndex';

function createTree (input, methodLookup, terminalNodes = []) {
  function formTree (index) {
    let current = input[index];
    let nextChild = index;
    current = {
      childCount: current[1],
      name: methodLookup[current[0]],
      onStack: current[3][0],
      onCPU: current[3][1],
    };
    if (current.childCount !== 0) {
      for (let i = 0; i < current.childCount; i++) {
        if (!current.children) current = { ...current, children: [] };
        if (nextChild === input.length - 1) break;
        const returnValue = formTree(nextChild + 1);
        returnValue.node && (returnValue.node.parent = [current]);
        nextChild = returnValue.index;
        current.children = [...current.children, returnValue.node];
      }
    }
    if (current.childCount === 0) {
      const existingNodeIndex = findIndex(terminalNodes, 'name', current.name);
      if (existingNodeIndex > -1) {
        // node really exists
        const node = terminalNodes[existingNodeIndex];
        const newNode = {
          onStack: node.onStack + current.onStack,
          onCPU: node.onCPU + current.onCPU,
          name: node.name,
          members: node.members ? [...node.members, current] : [node, current],
        };
        terminalNodes.splice(existingNodeIndex, 1, newNode);
      } else {
        terminalNodes.push(current);
      }
    }
    return { index: nextChild, node: current };
  }
  return {
    treeRoot: formTree(0).node,
    terminalNodes: terminalNodes.sort((a, b) => b.onCPU - a.onCPU).slice(0, 4),
  };
}

export default function (state = {}, action) {
  switch (action.type) {
    case GET_CPU_SAMPLING_REQUEST:
      return {
        asyncStatus: 'PENDING',
      };

    case GET_CPU_SAMPLING_SUCCESS: {
      const { profile: { frame_nodes }, method_lookup } = action.res;
      return {
        asyncStatus: 'SUCCESS',
        data: createTree(frame_nodes, method_lookup),
      };
    }

    case GET_CPU_SAMPLING_FAILURE:
      return {
        asyncStatus: 'ERROR',
      };

    default: return state;
  }
}
