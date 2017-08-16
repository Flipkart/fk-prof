package fk.prof.userapi.api;

import com.codahale.metrics.Timer;
import fk.prof.metrics.ProcessGroupTag;
import fk.prof.userapi.model.AggregatedOnCpuSamples;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.tree.CallTreeView;
import fk.prof.userapi.model.tree.CalleesTreeView;
import fk.prof.userapi.util.MetricsUtil;

/**
 * Created by gaurav.ashok on 08/08/17.
 */
public class ProfileViewCreator {

    private static String callerTreeViewCreationTimerPrefix ="views.calltree.create.timer";
    private static String calleeTreeViewCreationTimerPrefix = "views.calleetree.create.timer";

    public CallTreeView buildCallTreeView(AggregatedProfileInfo profile, String traceName) {
        ProcessGroupTag pgTag = new ProcessGroupTag(profile.getAppId(), profile.getClusterId(), profile.getProcId());
        try(Timer.Context ctx = MetricsUtil.timer(callerTreeViewCreationTimerPrefix, pgTag.toString()).time()) {
            AggregatedOnCpuSamples samplesData = (AggregatedOnCpuSamples) profile.getAggregatedSamples(traceName).getAggregatedSamples();
            return new CallTreeView(samplesData.getCallTree());
        }
    }

    public CalleesTreeView buildCalleesTreeView(AggregatedProfileInfo profile, String traceName) {
        ProcessGroupTag pgTag = new ProcessGroupTag(profile.getAppId(), profile.getClusterId(), profile.getProcId());
        try(Timer.Context ctx = MetricsUtil.timer(calleeTreeViewCreationTimerPrefix, pgTag.toString()).time()) {
            AggregatedOnCpuSamples samplesData = (AggregatedOnCpuSamples) profile.getAggregatedSamples(traceName).getAggregatedSamples();
            return new CalleesTreeView(samplesData.getCallTree());
        }
    }
}
