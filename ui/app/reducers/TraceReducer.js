import {
  GET_TRACES_REQUEST,
  GET_TRACES_SUCCESS,
  GET_TRACES_FAILURE,
} from 'actions/TraceActions';

export const getUniqueId = r => `${r.app}/${r.cluster}/${r.proc}/${r.workType}`;

export default function (state = {}, action) {
  switch (action.type) {
    case GET_TRACES_REQUEST:
      return {
        ...state,
        [getUniqueId(action.req)]: {
          asyncStatus: 'PENDING',
          data: [],
        },
      };

    case GET_TRACES_SUCCESS:
      return {
        ...state,
        [getUniqueId(action.req)]: {
          asyncStatus: 'SUCCESS',
          data: action.res,
        },
      };

    case GET_TRACES_FAILURE:
      return {
        ...state,
        [getUniqueId(action.req)]: {
          asyncStatus: 'ERROR',
        },
      };

    default: return state;
  }
}
