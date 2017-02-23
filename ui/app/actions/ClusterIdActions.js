import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_CLUSTER_IDS_REQUEST = 'GET_CLUSTER_IDS_REQUEST';
export const GET_CLUSTER_IDS_SUCCESS = 'GET_CLUSTER_IDS_SUCCESS';
export const GET_CLUSTER_IDS_FAILURE = 'GET_CLUSTER_IDS_FAILURE';

export function getClusterIdsRequestAction () {
  return { type: GET_CLUSTER_IDS_REQUEST };
}

export function getClusterIdsSuccessAction (clusterIds) {
  return { type: GET_CLUSTER_IDS_SUCCESS, data: clusterIds };
}

export function getClusterIdsFailureAction (error) {
  return { type: GET_CLUSTER_IDS_FAILURE, error };
}

export function fetchClusterIdsAction ({ appId, query }) {
  return (dispatch) => {
    dispatch(getClusterIdsRequestAction({ req: appId }));
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps${appId}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return fetch(url)
      .then(response => response.json())
      .then(json => dispatch(getClusterIdsSuccessAction({ res: json, req: appId })))
      .catch(err => dispatch(getClusterIdsFailureAction(err)));
  };
}
