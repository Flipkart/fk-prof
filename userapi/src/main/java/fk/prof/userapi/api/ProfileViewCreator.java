package fk.prof.userapi.api;

import fk.prof.userapi.cache.Cacheable;
import fk.prof.userapi.model.AggregatedOnCpuSamples;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.ProfileView;
import fk.prof.userapi.model.ProfileViewType;
import fk.prof.userapi.model.tree.CallTreeView;
import fk.prof.userapi.model.tree.CalleesTreeView;

/**
 * Created by gaurav.ashok on 08/08/17.
 */
public class ProfileViewCreator {

    public Cacheable<ProfileView> buildCacheableView(AggregatedProfileInfo profile, String traceName, ProfileViewType profileViewType) {
        switch (profileViewType) {
            case CALLERS:
                return buildCallTreeView(profile, traceName);
            case CALLEES:
                return buildCalleesTreeView(profile, traceName);
            default:
                throw new UnsupportedOperationException("ProfileViewType : " + profileViewType + " not supported");
        }
    }

    private CallTreeView buildCallTreeView(AggregatedProfileInfo profile, String traceName) {
        AggregatedOnCpuSamples samplesData = (AggregatedOnCpuSamples) profile.getAggregatedSamples(traceName).getAggregatedSamples();
        return new CallTreeView(samplesData.getCallTree());
    }

    private CalleesTreeView buildCalleesTreeView(AggregatedProfileInfo profile, String traceName) {
        AggregatedOnCpuSamples samplesData = (AggregatedOnCpuSamples) profile.getAggregatedSamples(traceName).getAggregatedSamples();
        return new CalleesTreeView(samplesData.getCallTree(), samplesData.getCallTree().getHotMethodNodeIds());
    }
}
