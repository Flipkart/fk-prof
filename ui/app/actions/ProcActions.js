import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_PROCS_REQUEST = 'GET_PROCS_REQUEST';
export const GET_PROCS_SUCCESS = 'GET_PROCS_SUCCESS';
export const GET_PROCS_FAILURE = 'GET_PROCS_FAILURE';

export function getProcsRequestAction () {
  return { type: GET_PROCS_REQUEST };
}

export function getProcsSuccessAction (procIds) {
  return { type: GET_PROCS_SUCCESS, data: procIds };
}

export function getProcsFailureAction (error) {
  return { type: GET_PROCS_FAILURE, error };
}

export function fetchProcsAction ({ appId, clusterId, query }) {
  return (dispatch) => {
    dispatch(getProcsRequestAction({ req: clusterId }));
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps${appId}/${clusterId}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return fetch(url)
      .then(response => response.json())
      .then(json => dispatch(getProcsSuccessAction({ req: json, res: clusterId })))
      .catch(err => dispatch(getProcsFailureAction(err)));
  };
}
