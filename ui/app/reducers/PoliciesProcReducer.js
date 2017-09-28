import {
  GET_POLICIES_PROCS_FAILURE,
  GET_POLICIES_PROCS_REQUEST,
  GET_POLICIES_PROCS_SUCCESS
} from "actions/PoliciesProcActions";

export default function (state = {}, action) {
  switch (action.type) {
    case GET_POLICIES_PROCS_REQUEST:
      if (!state[action.req.policiesCluster]) {
        return {
          ...state,
          [action.req.policiesCluster]: {
            asyncStatus: 'PENDING',
            data: [],
          },
        };
      } else {
        return state;
      }

    case GET_POLICIES_PROCS_SUCCESS:
      return {
        ...state,
        [action.req.policiesCluster]: {
          asyncStatus: 'SUCCESS',
          data: [...new Set([...state[action.req.policiesCluster].data, ...action.res])],
        },
      };

    case GET_POLICIES_PROCS_FAILURE:
      return {
        ...state,
        [action.req.policiesCluster]: {
          asyncStatus: 'ERROR',
        },
      };

    default:
      return state;
  }
}
