import { combineReducers } from 'redux';

import apps from 'reducers/AppReducer';
import clusters from 'reducers/ClusterReducer';
import procs from 'reducers/ProcReducer';

export default combineReducers({
  apps,
  clusters,
  procs,
});
