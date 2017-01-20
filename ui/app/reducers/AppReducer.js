import {
  GET_APPS_REQUEST,
  GET_APPS_SUCCESS,
  GET_APPS_FAILURE,
} from 'actions/AppActions';

const INITIAL_STATE = {
  data: [],
  asyncStatus: 'INIT',
};

export default function (state = INITIAL_STATE, action) {
  switch (action.type) {
    case GET_APPS_REQUEST:
      return { ...state, asyncStatus: 'PENDING' };

    case GET_APPS_SUCCESS:
      return { data: action.data, asyncStatus: 'SUCCESS' };

    case GET_APPS_FAILURE:
      return { ...state, asyncStatus: 'ERROR' };

    default: return state;
  }
}
