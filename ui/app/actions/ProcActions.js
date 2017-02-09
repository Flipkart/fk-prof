import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

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
    const baseUrl = `/api/proc/${app}/${cluster}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return http.get(url)
      .then(json => dispatch(getProcsSuccessAction({ res: json, req: { app, cluster } })))
      .catch(err => dispatch(getProcsFailureAction({ err, req: { app, cluster } })));
  };
}
