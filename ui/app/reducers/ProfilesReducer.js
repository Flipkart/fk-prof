import {
  GET_PROFILES_REQUEST,
  GET_PROFILES_SUCCESS,
  GET_PROFILES_FAILURE,
} from 'actions/ProfileActions';

export const getUniqueId = r => `${r.app}/${r.cluster}/${r.proc}/${r.workType}`;

export default function (state = {}, action) {
  switch (action.type) {
    case GET_PROFILES_REQUEST:
      return {
        asyncStatus: 'PENDING',
      };

    case GET_PROFILES_SUCCESS:
      return {
        asyncStatus: 'SUCCESS',
        data: action.data,
      };

    case GET_PROFILES_FAILURE:
      return {
        asyncStatus: 'ERROR',
      };

    default: return state;
  }
}
