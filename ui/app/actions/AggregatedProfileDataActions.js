import http from 'utils/http';
import { objectToQueryParams } from 'utils/UrlUtils';

export const AGGREGATED_PROFILE_DATA_REQUEST = 'AGGREGATED_PROFILE_DATA_REQUEST';
export const AGGREGATED_PROFILE_DATA_SUCCESS = 'AGGREGATED_PROFILE_DATA_SUCCESS';
export const AGGREGATED_PROFILE_DATA_FAILURE = 'AGGREGATED_PROFILE_DATA_FAILURE';

const workTypeMap = {
  cpu_sample_work: 'cpu-sampling',
};

export function getAggregatedProfileDataRequestAction (req) {
  return { type: AGGREGATED_PROFILE_DATA_REQUEST, ...req };
}

export function getAggregatedProfileDataSuccessAction (data) {
  return { type: AGGREGATED_PROFILE_DATA_SUCCESS, ...data };
}

export function getAggregatedProfileDataFailureAction ({ error, req }) {
  return { type: AGGREGATED_PROFILE_DATA_FAILURE, error, req };
}

export default function fetchAggregatedProfileDataAction (req) {
  return (dispatch) => {
    dispatch(getAggregatedProfileDataRequestAction({ req }));
    const queryParams = objectToQueryParams(req.query);
    const baseUrl = `/api/profile/${req.app}/${req.cluster}/${req.proc}/${workTypeMap[req.workType || req.selectedWorkType]}/${req.traceName}`;
    const url = queryParams ? `${baseUrl}?${queryParams}` : baseUrl;
    return http.get(url)
      .then(response => dispatch(getAggregatedProfileDataSuccessAction({ res: response, req })))
      .catch(err => dispatch(getAggregatedProfileDataFailureAction({ err, req })));
  };
}
