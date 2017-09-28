import http from "utils/http";
import {objectToQueryParams} from "utils/UrlUtils";

export const GET_POLICIES_CLUSTERS_REQUEST = 'GET_POLICIES_CLUSTERS_REQUEST';
export const GET_POLICIES_CLUSTERS_SUCCESS = 'GET_POLICIES_CLUSTERS_SUCCESS';
export const GET_POLICIES_CLUSTERS_FAILURE = 'GET_POLICIES_CLUSTERS_FAILURE';

export function getPolicyClustersRequestAction(req) {
  return {type: GET_POLICIES_CLUSTERS_REQUEST, ...req};
}

export function getPolicyClustersSuccessAction(data) {
  return {type: GET_POLICIES_CLUSTERS_SUCCESS, ...data};
}

export function getPolicyClustersFailureAction({error, req}) {
  return {type: GET_POLICIES_CLUSTERS_FAILURE, error, req};
}

export default function fetchPolicyClustersAction(policiesApp, prefix) {
  return (dispatch) => {
    dispatch(getPolicyClustersRequestAction({req: {policiesApp}}));
    const queryParams = prefix ? '?' + objectToQueryParams({prefix}) : '';
    const baseUrl = `/api/meta/policies/clusters/${policiesApp}`;
    const url = `${baseUrl}${queryParams}`;
    return http.get(url)
      .then(res => dispatch(getPolicyClustersSuccessAction({res, req: {policiesApp}})))
      .catch(err => dispatch(getPolicyClustersFailureAction({err, req: {policiesApp}})));
  };
}
