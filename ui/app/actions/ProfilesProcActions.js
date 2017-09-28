import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_PROFILES_PROCS_REQUEST = 'GET_PROFILES_PROCS_REQUEST';
export const GET_PROFILES_PROCS_SUCCESS = 'GET_PROFILES_PROCS_SUCCESS';
export const GET_PROFILES_PROCS_FAILURE = 'GET_PROFILES_PROCS_FAILURE';

export function getProfilesProcsRequestAction (req) {
  return { type: GET_PROFILES_PROCS_REQUEST, ...req };
}

export function getProfilesProcsSuccessAction (data) {
  return { type: GET_PROFILES_PROCS_SUCCESS, ...data };
}

export function getProfilesProcsFailureAction (error, req) {
  return { type: GET_PROFILES_PROCS_FAILURE, error, req };
}

export default function fetchProcsAction(profilesApp, profilesCluster, prefix) {
  return (dispatch) => {
    dispatch(getProfilesProcsRequestAction({ req: { profilesCluster } }));
    const queryParams = prefix ? '?' + objectToQueryParams({prefix}) : '';
    const baseUrl = `/api/meta/profiles/procs/${profilesApp}/${profilesCluster}`;
    const url = `${baseUrl}${queryParams}`;
    return http.get(url)
      .then(json => dispatch(getProfilesProcsSuccessAction({ res: json, req: { profilesApp, profilesCluster } })))
      .catch(err => dispatch(getProfilesProcsFailureAction({ err, req: { profilesApp, profilesCluster } })));
  };
}
