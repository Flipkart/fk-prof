// you can create different actions files based on your functionality and categorization
import fetch from 'isomorphic-fetch';
import mockAppIds from '../../api-mocks/apps.json';
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
    const url = queryParams ? `/apps?${queryParams}` : '/app';
    // return fetch(url)
    //   .then(response => response.json())
    //   .then(json => dispatch(getAppIdsSuccessAction(json))) // success, send the data to reducers
    //   .catch(err => dispatch(getAppIdsFailureAction(err))); // for error
    return Promise.resolve()
      .then(_ => dispatch(getAppIdsSuccessAction(mockAppIds)));
  };
}
