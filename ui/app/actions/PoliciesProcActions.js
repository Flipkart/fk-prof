import http from "utils/http";
import {objectToQueryParams} from "utils/UrlUtils";

export const GET_POLICIES_PROCS_REQUEST = 'GET_POLICIES_PROCS_REQUEST';
export const GET_POLICIES_PROCS_SUCCESS = 'GET_POLICIES_PROCS_SUCCESS';
export const GET_POLICIES_PROCS_FAILURE = 'GET_POLICIES_PROCS_FAILURE';

export function getPoliciesProcsRequestAction(req) {
  return {type: GET_POLICIES_PROCS_REQUEST, ...req};
}

export function getPoliciesProcsSuccessAction(data) {
  return {type: GET_POLICIES_PROCS_SUCCESS, ...data};
}

export function getPoliciesProcsFailureAction({error, req}) {
  return {type: GET_POLICIES_PROCS_FAILURE, error, req};
}

export default function fetchPoliciesProcsAction(policiesApp, policiesCluster, prefix) {
  return (dispatch) => {
    dispatch(getPoliciesProcsRequestAction({req: {policiesApp, policiesCluster}}));
    const queryParams = prefix ? '?' + objectToQueryParams({prefix}) : '';
    const baseUrl = `/api/meta/policies/procs/${policiesApp}/${policiesCluster}`;
    const url = `${baseUrl}${queryParams}`;
    return http.get(url)
      .then(json => dispatch(getPoliciesProcsSuccessAction({res: json, req: {policiesApp, policiesCluster}})))
      .catch(err => dispatch(getPoliciesProcsFailureAction({err, req: {policiesApp, policiesCluster}})));
  };
}
