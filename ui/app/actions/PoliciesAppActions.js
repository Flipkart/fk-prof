/**
 * Created by rohit.patiyal on 12/07/17.
 */
import http from 'utils/http';
import {objectToQueryParams} from 'utils/UrlUtils';

export const GET_POLICIES_APPS_REQUEST = 'GET_POLICIES_APPS_REQUEST';
export const GET_POLICIES_APPS_SUCCESS = 'GET_POLICIES_APPS_SUCCESS';
export const GET_POLICIES_APPS_FAILURE = 'GET_POLICIES_APPS_FAILURE';

export function getPolicyAppIdsRequestAction() {
  return {type: GET_POLICIES_APPS_REQUEST};
}

export function getPolicyAppIdsSuccessAction(policiesApps) {
  return {type: GET_POLICIES_APPS_SUCCESS, data: policiesApps};
}

export function getPolicyAppIdsFailureAction(error) {
  return {type: GET_POLICIES_APPS_FAILURE, error};
}

export default function fetchPolicyAppIdsAction(prefix) {
  return (dispatch) => {
    dispatch(getPolicyAppIdsRequestAction());
    const queryParams = prefix ? '?' + objectToQueryParams({prefix}) : '';
    const url = `/api/meta/policies/apps${queryParams}`;
    return http.get(url)
      .then(json => dispatch(getPolicyAppIdsSuccessAction(json))) // success, send the data to reducers
      .catch(err => dispatch(getPolicyAppIdsFailureAction(err))); // for error
  };
}
