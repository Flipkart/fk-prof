import fetch from 'isomorphic-fetch';
import { objectToQueryParams } from 'utils/UrlUtils';

import mockProfiles from '../../api-mocks/profiles.json';

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
    const baseUrl = `/profiles${app}/${cluster}/${proc}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return Promise.resolve(dispatch(getProfilesSuccessAction(mockProfiles)));
    return fetch(url)
      .then(response => response.json())
      .then(json => dispatch(getProfilesSuccessAction(json)))
      .catch(err => dispatch(getProfilesFailureAction(err)));
  };
}
