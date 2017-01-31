import { combineReducers } from 'redux';

import apps from 'reducers/AppReducer';
import clusters from 'reducers/ClusterReducer';
import procs from 'reducers/ProcReducer';
import profiles from 'reducers/ProfilesReducer';
import traces from 'reducers/TraceReducer';
import cpuSampling from 'reducers/CPUSamplingReducer';

export default combineReducers({
  apps,
  clusters,
  procs,
  profiles,
  traces,
  cpuSampling,
});
