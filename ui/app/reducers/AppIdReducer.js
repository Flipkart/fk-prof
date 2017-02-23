import {
  GET_APP_IDS_REQUEST,
  GET_APP_IDS_SUCCESS,
  GET_APP_IDS_FAILURE,
} from 'actions/AppIdActions';

const INITIAL_STATE = {
  data: [],
  asyncStatus: 'INIT',
};

export default function (state = INITIAL_STATE, action) {
  switch (action.type) {
    case GET_APP_IDS_REQUEST:
      return { ...state, asyncStatus: 'PENDING' };

    case GET_APP_IDS_SUCCESS:
      return { data: action.data, asyncStatus: 'SUCCESS' };

    case GET_APP_IDS_FAILURE:
      return { ...state, asyncStatus: 'ERROR' };

    default: return state;
  }
}
