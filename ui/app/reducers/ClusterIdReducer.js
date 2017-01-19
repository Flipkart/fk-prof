import {
  GET_CLUSTER_IDS_REQUEST,
  GET_CLUSTER_IDS_SUCCESS,
  GET_CLUSTER_IDS_FAILURE,
} from 'actions/ClusterActions';

export default function (state = {}, action) {
  switch (action.type) {
    case GET_CLUSTER_IDS_REQUEST:
      return {
        ...state,
        [action.req.app]: {
          asyncStatus: 'PENDING',
          data: [],
        },
      };

    case GET_CLUSTER_IDS_SUCCESS:
      return {
        ...state,
        [action.req.app]: {
          asyncStatus: 'SUCCESS',
          data: action.res,
        },
      };

    case GET_CLUSTER_IDS_FAILURE:
      return {
        ...state,
        [action.req.app]: {
          asyncStatus: 'ERROR',
        },
      };

    default: return state;
  }
}
