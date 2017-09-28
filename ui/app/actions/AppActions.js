import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_APPS_REQUEST = 'GET_APPS_REQUEST';
export const GET_APPS_SUCCESS = 'GET_APPS_SUCCESS';
export const GET_APPS_FAILURE = 'GET_APPS_FAILURE';

export function getAppIdsRequestAction () {
  return { type: GET_APPS_REQUEST };
}

export function getAppIdsSuccessAction (appIds) {
  return { type: GET_APPS_SUCCESS, data: appIds };
}

export function getAppIdsFailureAction (error) {
  return { type: GET_APPS_FAILURE, error };
}

export default function fetchAppIdsAction (prefix) {
  return (dispatch) => {
    dispatch(getAppIdsRequestAction());
    const queryParams = objectToQueryParams({ prefix });
    const url = queryParams ? `/api/apps?${queryParams}` : '/app';
    return http.get(url)
      .then(json => dispatch(getAppIdsSuccessAction(json))) // success, send the data to reducers
      .catch(err => dispatch(getAppIdsFailureAction(err))); // for error
  };
}
