import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_PROC_IDS_REQUEST = 'GET_PROC_IDS_REQUEST';
export const GET_PROC_IDS_SUCCESS = 'GET_PROC_IDS_SUCCESS';
export const GET_PROC_IDS_FAILURE = 'GET_PROC_IDS_FAILURE';

export function getProcIdsRequestAction () {
  return { type: GET_PROC_IDS_REQUEST };
}

export function getProcIdsSuccessAction (procIds) {
  return { type: GET_PROC_IDS_SUCCESS, data: procIds };
}

export function getProcIdsFailureAction (error) {
  return { type: GET_PROC_IDS_FAILURE, error };
}

export function fetchProcIdsAction ({ appId, clusterId, query }) {
  return (dispatch) => {
    dispatch(getProcIdsRequestAction());
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps${appId}/${clusterId}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return fetch(url)
      .then(response => response.json())
      .then(json => dispatch(getProcIdsSuccessAction(json)))
      .catch(err => dispatch(getProcIdsFailureAction(err)));
  };
}
