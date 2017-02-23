import { objectToQueryParams } from 'utils/UrlUtils';
import http from 'utils/http';

import mockCluster from '../../api-mocks/cluster-ids.json';

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
    const baseUrl = `/apps/${app}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    // return http.get(url)
    //   .then(response => response.json())
    return Promise.resolve()
      .then(() => dispatch(getClustersSuccessAction({ res: mockCluster, req: { app } })))
      // .then(json => dispatch(getClustersSuccessAction({ res: json, req: app })))
      .catch(err => dispatch(getClustersFailureAction({ err, req: { app } })));
  };
}
