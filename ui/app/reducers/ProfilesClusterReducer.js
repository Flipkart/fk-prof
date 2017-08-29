import {
  GET_PROFILES_CLUSTERS_REQUEST,
  GET_PROFILES_CLUSTERS_SUCCESS,
  GET_PROFILES_CLUSTERS_FAILURE,
} from 'actions/ProfilesClusterActions';

export default function (state = {}, action) {
  switch (action.type) {
    case GET_PROFILES_CLUSTERS_REQUEST:
      if (!state[action.req.profilesApp]) {
        return {
          ...state,
          [action.req.profilesApp]: {
            asyncStatus: 'PENDING',
            data: []
          },
        };
      } else {
        return state;
      }
    case GET_PROFILES_CLUSTERS_SUCCESS:
      return {
        ...state,
        [action.req.profilesApp]: {
          asyncStatus: 'SUCCESS',
          data: [...new Set([...state[action.req.profilesApp].data, ...action.res])],
        },
      };

    case GET_PROFILES_CLUSTERS_FAILURE:
      return {
        ...state,
        [action.req.profilesApp]: {
          asyncStatus: 'ERROR',
        },
      };

    default: return state;
  }
}
