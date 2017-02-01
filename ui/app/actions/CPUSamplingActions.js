import http from 'utils/http';

export const GET_CPU_SAMPLING_REQUEST = 'GET_CPU_SAMPLING_REQUEST';
export const GET_CPU_SAMPLING_SUCCESS = 'GET_CPU_SAMPLING_SUCCESS';
export const GET_CPU_SAMPLING_FAILURE = 'GET_CPU_SAMPLING_FAILURE';

export function getCPUSamplingRequestAction (req) {
  return { type: GET_CPU_SAMPLING_REQUEST, ...req };
}

export function getCPUSamplingSuccessAction (data) {
  return { type: GET_CPU_SAMPLING_SUCCESS, ...data };
}

export function getCPUSamplingFailureAction ({ error, req }) {
  return { type: GET_CPU_SAMPLING_FAILURE, error, req };
}

export default function fetchCPUSamplingAction (req) {
  return (dispatch) => {
    dispatch(getCPUSamplingRequestAction({ req }));
    const baseUrl = '/stacktrace';
    return http.get(baseUrl)
      .then(response => dispatch(getCPUSamplingSuccessAction({ res: response, req })));
  };
}
