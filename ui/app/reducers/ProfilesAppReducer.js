import {
  GET_PROFILES_APPS_REQUEST,
  GET_PROFILES_APPS_SUCCESS,
  GET_PROFILES_APPS_FAILURE,
} from 'actions/ProfilesAppActions';

const INITIAL_STATE = {
  data: [],
  asyncStatus: 'INIT',
};

export default function (state = INITIAL_STATE, action) {
  switch (action.type) {
    case GET_PROFILES_APPS_REQUEST:
      return { ...state, asyncStatus: 'PENDING' };

    case GET_PROFILES_APPS_SUCCESS:
      return { data: [...new Set([...state.data, ...action.data])], asyncStatus: 'SUCCESS' };

    case GET_PROFILES_APPS_FAILURE:
      return { ...state, asyncStatus: 'ERROR' };

    default: return state;
  }
}
