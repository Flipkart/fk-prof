import { objectToQueryParams } from 'utils/UrlUtils';
import http from 'utils/http';

import mockCluster from '../../api-mocks/cluster-ids.json';

export const GET_CLUSTER_IDS_REQUEST = 'GET_CLUSTER_IDS_REQUEST';
export const GET_CLUSTER_IDS_SUCCESS = 'GET_CLUSTER_IDS_SUCCESS';
export const GET_CLUSTER_IDS_FAILURE = 'GET_CLUSTER_IDS_FAILURE';

export function getClustersRequestAction (req) {
  return { type: GET_CLUSTER_IDS_REQUEST, ...req };
}

export function getClustersSuccessAction (clusterIds) {
  return { type: GET_CLUSTER_IDS_SUCCESS, data: clusterIds };
}

export function getClustersFailureAction ({ error, req }) {
  return { type: GET_CLUSTER_IDS_FAILURE, error, ...req };
}

export default function fetchClustersAction ({ app, query }) {
  return (dispatch) => {
    dispatch(getClustersRequestAction({ req: app }));
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps/${app}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    // return http.get(url)
    //   .then(response => response.json())
    return Promise.resolve()
      .then(() => dispatch(getClustersSuccessAction({ res: mockCluster, req: app })))
      // .then(json => dispatch(getClustersSuccessAction({ res: json, req: app })))
      .catch(err => dispatch(getClustersFailureAction({ err, req: app })));
  };
}
