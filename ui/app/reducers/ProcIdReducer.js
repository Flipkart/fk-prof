import {
  GET_PROC_IDS_REQUEST,
  GET_PROC_IDS_SUCCESS,
  GET_PROC_IDS_FAILURE,
} from 'actions/ProcIdActions';

export default function (state = {}, action) {
  switch (action.type) {
    case GET_PROC_IDS_REQUEST:
      return {
        ...state,
        [action.req.clusterId]: {
          asyncStatus: 'PENDING',
          data: [],
        },
      };

    case GET_PROC_IDS_SUCCESS:
      return {
        ...state,
        [action.req.clusterId]: {
          asyncStatus: 'SUCCESS',
          data: action.res,
        },
      };

    case GET_PROC_IDS_FAILURE:
      return {
        ...state,
        [action.req.clusterId]: {
          asyncStatus: 'ERROR',
        },
      };

    default: return state;
  }
}
