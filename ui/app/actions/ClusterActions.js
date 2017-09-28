import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_CLUSTERS_REQUEST = 'GET_CLUSTERS_REQUEST';
export const GET_CLUSTERS_SUCCESS = 'GET_CLUSTERS_SUCCESS';
export const GET_CLUSTERS_FAILURE = 'GET_CLUSTERS_FAILURE';

export function getClustersRequestAction (req) {
  return { type: GET_CLUSTERS_REQUEST, ...req };
}

export function getClustersSuccessAction (data) {
  return { type: GET_CLUSTERS_SUCCESS, ...data };
}

export function getClustersFailureAction ({ error, req }) {
  return { type: GET_CLUSTERS_FAILURE, error, req };
}

export default function fetchClustersAction ({ app, query }) {
  return (dispatch) => {
    dispatch(getClustersRequestAction({ req: { app } }));
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/api/cluster/${app}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return http.get(url)
      .then(res => dispatch(getClustersSuccessAction({ res, req: { app } })))
      .catch(err => dispatch(getClustersFailureAction({ err, req: { app } })));
  };
}
