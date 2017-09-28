import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_PROFILES_CLUSTERS_REQUEST = 'GET_PROFILES_CLUSTERS_REQUEST';
export const GET_PROFILES_CLUSTERS_SUCCESS = 'GET_PROFILES_CLUSTERS_SUCCESS';
export const GET_PROFILES_CLUSTERS_FAILURE = 'GET_PROFILES_CLUSTERS_FAILURE';

export function getClustersRequestAction (req) {
  return { type: GET_PROFILES_CLUSTERS_REQUEST, ...req };
}

export function getClustersSuccessAction (data) {
  return { type: GET_PROFILES_CLUSTERS_SUCCESS, ...data };
}

export function getClustersFailureAction ({ error, req }) {
  return { type: GET_PROFILES_CLUSTERS_FAILURE, error, req };
}

export default function fetchClustersAction(profilesApp, prefix) {
  return (dispatch) => {
    dispatch(getClustersRequestAction({ req: { profilesApp } }));
    const queryParams = prefix ? '?' + objectToQueryParams({prefix}) : '';
    const baseUrl = `/api/meta/profiles/clusters/${profilesApp}`;
    const url = `${baseUrl}${queryParams}`;
    return http.get(url)
      .then(res => dispatch(getClustersSuccessAction({ res, req: { profilesApp } })))
      .catch(err => dispatch(getClustersFailureAction({ err, req: { profilesApp } })));
  };
}
