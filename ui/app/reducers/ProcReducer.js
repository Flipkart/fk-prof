import {
  GET_PROCS_REQUEST,
  GET_PROCS_SUCCESS,
  GET_PROCS_FAILURE,
} from 'actions/ProcActions';

export default function (state = {}, action) {
  switch (action.type) {
    case GET_PROCS_REQUEST:
      return {
        ...state,
        [action.req.clusterId]: {
          asyncStatus: 'PENDING',
          data: [],
        },
      };

    case GET_PROCS_SUCCESS:
      return {
        ...state,
        [action.req.clusterId]: {
          asyncStatus: 'SUCCESS',
          data: action.res,
        },
      };

    case GET_PROCS_FAILURE:
      return {
        ...state,
        [action.req.clusterId]: {
          asyncStatus: 'ERROR',
        },
      };

    default: return state;
  }
}
