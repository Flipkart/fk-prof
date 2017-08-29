import {
  GET_POLICIES_CLUSTERS_FAILURE,
  GET_POLICIES_CLUSTERS_REQUEST,
  GET_POLICIES_CLUSTERS_SUCCESS
} from "actions/PoliciesClusterActions";

export default function (state = {}, action) {
  switch (action.type) {
    case GET_POLICIES_CLUSTERS_REQUEST:
      if (!state[action.req.policiesApp]) {
        return {
          ...state,
          [action.req.policiesApp]: {
            asyncStatus: 'PENDING',
            data: [],
          },
        };
      } else {
        return state;
      }
    case GET_POLICIES_CLUSTERS_SUCCESS:
      return {
        ...state,
        [action.req.policiesApp]: {
          asyncStatus: 'SUCCESS',
          data: [...new Set([...state[action.req.policiesApp].data, ...action.res])],
        },
      };

    case GET_POLICIES_CLUSTERS_FAILURE:
      return {
        ...state,
        [action.req.policiesApp]: {
          asyncStatus: 'ERROR',
        },
      };

    default:
      return state;
  }
}
