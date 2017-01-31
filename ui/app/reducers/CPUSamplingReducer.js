import {
  GET_CPU_SAMPLING_REQUEST,
  GET_CPU_SAMPLING_SUCCESS,
  GET_CPU_SAMPLING_FAILURE,
} from 'actions/CPUSamplingActions';

function createTree (input, methodLookup) {
  function formTree (index) {
    let current = input[index];
    let nextChild = index;
    current = {
      childrenCount: current[1],
      name: methodLookup[current[0]],
    };
    if (current.childrenCount !== 0) {
      for (let i = 0; i < current.childrenCount; i++) {
        if (!current.children) current = { ...current, children: [] };
        if (nextChild === input.length - 1) break;
        const returnValue = formTree(nextChild + 1);
        nextChild = returnValue.index;
        current.children = [...current.children, returnValue.node];
      }
    }
    return { index: nextChild, node: current };
  }
  return formTree(0).node;
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
