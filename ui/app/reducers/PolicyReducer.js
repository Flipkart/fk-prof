import {
  POLICY_REQUEST,
  POLICY_SUCCESS,
  POLICY_FAILURE,
  POLICY_DESCRIPTION_CHANGE,
  POLICY_SCHEDULE_CHANGE,
  POLICY_WORK_CHANGE,
  FETCH_POLICY,
} from "../actions/PolicyActions";

const INITIAL_STATE = {
  data:
    {
      version: -1,
      policyDetails: {
        createdAt: "",
        modifiedAt: "",
        modifiedBy: "Anonymous",
        policy: {
          description: "",
          schedule: {
            duration: 120,
            pgCovPct: 10,
            after: 0
          },
          work: [{
            wType: "cpu_sample_work",
            cpuSample: {
              frequency: 50,
              maxFrames: 64
            }
          }]
        }
      }
    },
  reqType: '',
  asyncStatus: 'INIT',
  error: '',
};

export default function (state = INITIAL_STATE, action) {
  switch (action.type) {
    case POLICY_REQUEST:
      if (action.reqType === FETCH_POLICY) {
        return {...INITIAL_STATE, asyncStatus: 'PENDING', reqType: action.reqType}
      } else {
        return {...state, asyncStatus: 'PENDING', reqType: action.reqType};
      }
    case POLICY_SUCCESS:
      return {data: action.data, asyncStatus: 'SUCCESS', reqType: action.reqType};
    case POLICY_FAILURE:
      return {...state, error: action.error, asyncStatus: 'ERROR', reqType: action.reqType};
    case POLICY_DESCRIPTION_CHANGE:
      return {
        ...state, data: {
          version: state.data.version,
          policyDetails: {
            ...state.data.policyDetails,
            policy: {...state.data.policyDetails.policy, description: action.description}
          }
        }
      };
    case POLICY_SCHEDULE_CHANGE:
      return {
        ...state, data: {
          version: state.data.version,
          policyDetails: {
            ...state.data.policyDetails,
            policy: {...state.data.policyDetails.policy, schedule: action.schedule}
          }
        },
      };
    case POLICY_WORK_CHANGE:
      return {
        ...state, data:
          {
            version: state.data.version,
            policyDetails: {
              ...state.data.policyDetails, policy: {
                ...state.data.policyDetails.policy, work: action.work
              }
            },
          }
      };
    default:
      return state;
  }
}
