import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_TRACES_REQUEST = 'GET_TRACES_REQUEST';
export const GET_TRACES_SUCCESS = 'GET_TRACES_SUCCESS';
export const GET_TRACES_FAILURE = 'GET_TRACES_FAILURE';

export function getTracesRequestAction () {
  return { type: GET_TRACES_REQUEST };
}

export function getTracesSuccessAction (traces) {
  return { type: GET_TRACES_SUCCESS, data: traces };
}

export function getTracesFailureAction (error) {
  return { type: GET_TRACES_FAILURE, error };
}

export function fetchTracesAction ({ appId, clusterId, procId, workType, query }) {
  return (dispatch) => {
    dispatch(getTracesRequestAction());
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps${appId}/${clusterId}/${procId}/${workType}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return fetch(url)
      .then(response => response.json())
      .then(json => dispatch(getTracesSuccessAction(json)))
      .catch(err => dispatch(getTracesFailureAction(err)));
  };
}
