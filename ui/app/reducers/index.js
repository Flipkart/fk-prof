import { combineReducers } from 'redux';

import appIds from 'reducers/AppIdReducer';
import clusterIds from 'reducers/ClusterIdReducer';
import procIds from 'reducers/ProcIdReducer';

export default combineReducers({
  appIds,
  clusterIds,
  procIds,
});
