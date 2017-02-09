import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_PROFILES_REQUEST = 'GET_PROFILES_REQUEST';
export const GET_PROFILES_SUCCESS = 'GET_PROFILES_SUCCESS';
export const GET_PROFILES_FAILURE = 'GET_PROFILES_FAILURE';

export function getProfilesRequestAction () {
  return { type: GET_PROFILES_REQUEST };
}

export function getProfilesSuccessAction (profiles) {
  return { type: GET_PROFILES_SUCCESS, data: profiles };
}

export function getProfilesFailureAction (error) {
  return { type: GET_PROFILES_FAILURE, error };
}

export function fetchProfilesAction ({ app, cluster, proc, query }) {
  return (dispatch) => {
    dispatch(getProfilesRequestAction());
    const queryParams = objectToQueryParams(query);
    const baseUrl = `/api/profiles/${app}/${cluster}/${proc}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return http.get(url)
      .then(json => dispatch(getProfilesSuccessAction(json)))
      .catch(err => dispatch(getProfilesFailureAction(err)));
  };
}
