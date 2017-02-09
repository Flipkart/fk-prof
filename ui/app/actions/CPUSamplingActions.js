import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const GET_CPU_SAMPLING_REQUEST = 'GET_CPU_SAMPLING_REQUEST';
export const GET_CPU_SAMPLING_SUCCESS = 'GET_CPU_SAMPLING_SUCCESS';
export const GET_CPU_SAMPLING_FAILURE = 'GET_CPU_SAMPLING_FAILURE';

const cpuSampleMap = {
  cpu_sample_work: 'cpu-sampling',
};

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
    const queryParams = objectToQueryParams(req.query);
    const baseUrl = `/api/profile/${req.app}/${req.cluster}/${req.proc}/${cpuSampleMap[req.workType]}/${req.traceName}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return http.get(url)
      .then(response => dispatch(getCPUSamplingSuccessAction({ res: response, req })))
      .catch(err => dispatch(getCPUSamplingFailureAction({ err, req })));
  };
}
