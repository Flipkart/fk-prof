import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_PROFILES_APPS_REQUEST = 'GET_PROFILES_APPS_REQUEST';
export const GET_PROFILES_APPS_SUCCESS = 'GET_PROFILES_APPS_SUCCESS';
export const GET_PROFILES_APPS_FAILURE = 'GET_PROFILES_APPS_FAILURE';

export function getProfilesAppsRequestAction () {
  return { type: GET_PROFILES_APPS_REQUEST };
}

export function getProfilesAppsSuccessAction (profilesApp) {
  return { type: GET_PROFILES_APPS_SUCCESS, data: profilesApp };
}

export function getProfilesAppsFailureAction (error) {
  return { type: GET_PROFILES_APPS_FAILURE, error };
}

export default function fetchProfilesAppsAction (prefix) {
  return (dispatch) => {
    dispatch(getProfilesAppsRequestAction());
    const queryParams = prefix ? '?' + objectToQueryParams({ prefix }) : '';
    const url = `/api/meta/profiles/apps${queryParams}`;
    return http.get(url)
      .then(json => dispatch(getProfilesAppsSuccessAction(json))) // success, send the data to reducers
      .catch(err => dispatch(getProfilesAppsFailureAction(err))); // for error
  };
}

