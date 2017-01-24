import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

import mockTraces from '../../api-mocks/traces.json';

export const GET_TRACES_REQUEST = 'GET_TRACES_REQUEST';
export const GET_TRACES_SUCCESS = 'GET_TRACES_SUCCESS';
export const GET_TRACES_FAILURE = 'GET_TRACES_FAILURE';

export function getTracesRequestAction (req) {
  return { type: GET_TRACES_REQUEST, ...req };
}

export function getTracesSuccessAction (data) {
  return { type: GET_TRACES_SUCCESS, ...data };
}

export function getTracesFailureAction (error, req) {
  return { type: GET_TRACES_FAILURE, error, req };
}

export default function fetchTracesAction ({ app, cluster, proc, workType, query }) {
  return (dispatch) => {
    dispatch(getTracesRequestAction({
      req: {
        app, cluster, proc, workType,
      },
    }));
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/apps${app}/${cluster}/${proc}/${workType}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    // return fetch(url)
    //   .then(response => response.json())
    //   .then(json => dispatch(getTracesSuccessAction(json)))
    //   .catch(err => dispatch(getTracesFailureAction(err)));
    return Promise.resolve()
      .then(() => dispatch(getTracesSuccessAction({
        res: mockTraces,
        req: {
          app, cluster, proc, workType,
        },
      })))
      .catch(err => dispatch(getTracesFailureAction({
        err,
        req: {
          app, cluster, proc, workType,
        },
      })));
  };
}
