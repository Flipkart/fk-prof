import { combineReducers } from 'redux';

import profilesApps from 'reducers/ProfilesAppReducer';
import profilesClusters from 'reducers/ProfilesClusterReducer';
import profilesProcs from 'reducers/ProfilesProcReducer';
import profiles from 'reducers/ProfilesReducer';
import aggregatedProfileData from 'reducers/AggregatedProfileDataReducer';
import policiesApps from 'reducers/PoliciesAppReducer';
import policiesClusters from 'reducers/PoliciesClusterReducer';
import policiesProcs from 'reducers/PoliciesProcReducer';

export default combineReducers({
  profilesApps,
  profilesClusters,
  profilesProcs,
  profiles,
  aggregatedProfileData,
  policiesApps,
  policiesClusters,
  policiesProcs,
});
