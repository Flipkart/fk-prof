import http from '../utils/http';

export const FETCH_POLICY = 'FETCH_POLICY';
export const CREATE_POLICY = 'CREATE_POLICY';
export const UPDATE_POLICY = 'UPDATE_POLICY';

export const POLICY_REQUEST = 'POLICY_REQUEST';
export const POLICY_SUCCESS = 'POLICY_SUCCESS';
export const POLICY_FAILURE = 'POLICY_FAILURE';

export const POLICY_SCHEDULE_CHANGE = 'POLICY_SCHEDULE_CHANGE';
export const POLICY_DESCRIPTION_CHANGE = 'POLICY_DESCRIPTION_CHANGE';
export const POLICY_WORK_CHANGE = 'POLICY_WORK_CHANGE';

export function policyRequestAction(reqType) {
  return {type: POLICY_REQUEST, reqType};
}

export function policySuccessAction(reqType, policy) {
  return {type: POLICY_SUCCESS, reqType, data: policy};
}

export function policyFailureAction(reqType, error) {
  return {type: POLICY_FAILURE, reqType, error};
}

export function policyScheduleChangeAction(schedule) {
  return dispatch => dispatch({type: POLICY_SCHEDULE_CHANGE, schedule});
}

export function policyDescriptionChangeAction(description) {
  return dispatch => dispatch({type: POLICY_DESCRIPTION_CHANGE, description});
}

export function policyWorkChangeAction(work) {
  return dispatch => dispatch({type: POLICY_WORK_CHANGE, work});
}

export function policyAction(reqType, policiesApp, policiesCluster, policiesProc, versionedPolicyDetails) {
  return (dispatch) => {
    dispatch(policyRequestAction(reqType));
    const url = `/api/policy/${policiesApp}/${policiesCluster}/${policiesProc}`;
    let req = undefined;
    let msg = undefined;
    switch (reqType) {
      case FETCH_POLICY:
        req = http.get(url);
        msg = 'Policy found';
        break;
      case CREATE_POLICY:
        req = http.post(url, versionedPolicyDetails);
        msg = 'Policy created';
        break;
      case UPDATE_POLICY:
        req = http.put(url, versionedPolicyDetails);
        msg = 'Policy updated';
        break;
      default:
        console.log('Unsupported request initiated');
        break;
    }
    if (req) {
      return req.then(json => {
        dispatch(policySuccessAction(reqType, json));
        return msg;
      }) // success, send the data to reducers
        .catch(err => {
          dispatch(policyFailureAction(reqType, err));
          throw(err.response.message);
        }); // for error
    }
    return null;
  }
}
