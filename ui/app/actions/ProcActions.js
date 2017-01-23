import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

import mockProcs from '../../api-mocks/proc-ids.json';

export const GET_PROCS_REQUEST = 'GET_PROCS_REQUEST';
export const GET_PROCS_SUCCESS = 'GET_PROCS_SUCCESS';
export const GET_PROCS_FAILURE = 'GET_PROCS_FAILURE';

export function getProcsRequestAction (req) {
  return { type: GET_PROCS_REQUEST, ...req };
}

export function getProcsSuccessAction (data) {
  return { type: GET_PROCS_SUCCESS, ...data };
}

export function getProcsFailureAction (error, req) {
  return { type: GET_PROCS_FAILURE, error, req };
}

export default function fetchProcsAction ({ app, cluster, query }) {
  return (dispatch) => {
    dispatch(getProcsRequestAction({ req: { cluster } }));
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps${app}/${cluster}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    // return fetch(url)
    //   .then(response => response.json())
    //   .then(json => dispatch(getProcsSuccessAction({ req: json, res: cluster })))
    //   .catch(err => dispatch(getProcsFailureAction(err)));
    return Promise.resolve()
      .then(() => dispatch(getProcsSuccessAction({ res: mockProcs, req: { app, cluster } })))
      // .then(json => dispatch(getClustersSuccessAction({ res: json, req: app })))
      .catch(err => dispatch(getProcsFailureAction({ err, req: { app, cluster } })));
  };
}
