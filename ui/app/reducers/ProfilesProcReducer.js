import {
  GET_PROFILES_PROCS_REQUEST,
  GET_PROFILES_PROCS_SUCCESS,
  GET_PROFILES_PROCS_FAILURE,
} from 'actions/ProfilesProcActions';

export default function (state = {}, action) {
  switch (action.type) {
    case GET_PROFILES_PROCS_REQUEST:
      if (!state[action.req.profilesCluster]) {
        return {
          ...state,
          [action.req.profilesCluster]: {
            asyncStatus: 'PENDING',
            data: [],
          },
        };
      } else {
        return state;
      }
    case GET_PROFILES_PROCS_SUCCESS:
      return {
        ...state,
        [action.req.profilesCluster]: {
          asyncStatus: 'SUCCESS',
          data: [...new Set([...state[action.req.profilesCluster].data, ...action.res])],
        },
      };

    case GET_PROFILES_PROCS_FAILURE:
      return {
        ...state,
        [action.req.profilesCluster]: {
          asyncStatus: 'ERROR',
        },
      };

    default: return state;
  }
}
