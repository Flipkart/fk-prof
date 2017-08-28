package fk.prof.userapi.util.proto;

import proto.PolicyDTO;

import java.util.List;

//TODO : Duplicate from backend, extract out in a common module
/**
 * Utility methods for policy proto
 * Created by rohit.patiyal on 22/05/17.
 */
public class PolicyDTOProtoUtil {
    private static String policyDetailsCompactRepr(PolicyDTO.PolicyDetails policyDetails) {
        return String.format("modAt=%s,creatAt=%s,creatBy=%s,policy=%s", policyDetails.getModifiedAt(), policyDetails.getCreatedAt(), policyDetails.getModifiedBy(), policyCompactRepr(policyDetails.getPolicy()));
    }

    private static String policyCompactRepr(PolicyDTO.Policy policy) {
        return String.format("desc:%s,sched:{%s},work:[%s]", policy.getDescription(),policyScheduleCompactRepr(policy.getSchedule()),policyWorkListCompactRepr(policy.getWorkList()));
    }

    private static String policyWorkListCompactRepr(List<PolicyDTO.Work> workList) {
        StringBuilder sb = new StringBuilder();
        for(PolicyDTO.Work work: workList){
            sb.append(policyWorkCompactRepr(work));
        }
        return sb.toString();
    }

    private static String policyWorkCompactRepr(PolicyDTO.Work work) {
        StringBuilder sb = new StringBuilder();
        if(work.hasCpuSample()){
            sb.append("cpuSample:");
            PolicyDTO.CpuSampleWork cpuSample = work.getCpuSample();
            sb.append(String.format("{freq=%d,maxFram=%d}",cpuSample.getFrequency(), cpuSample.getMaxFrames()));
        }
        if(work.hasThdSample()){
            sb.append("threadSample:");
            PolicyDTO.ThreadSampleWork threadSample = work.getThdSample();
            sb.append(String.format("{freq=%d,maxFram=%d}",threadSample.getFrequency(), threadSample.getMaxFrames()));
        }
        if(work.hasMonitorBlock()){
            sb.append("monitorBlock:");
            PolicyDTO.MonitorContentionWork monitorContention = work.getMonitorBlock();
            sb.append(String.format("{maxMon=%d,maxFram=%d}",monitorContention.getMaxMonitors(), monitorContention.getMaxFrames()));
        }
        if(work.hasMonitorWait()){
            sb.append("monitorWait:");
            PolicyDTO.MonitorWaitWork monitorWait = work.getMonitorWait();
            sb.append(String.format("{maxMon=%d,maxFram=%d}",monitorWait.getMaxMonitors(), monitorWait.getMaxFrames()));
        }
        return sb.toString();
    }

    private static String policyScheduleCompactRepr(PolicyDTO.Schedule schedule) {
        return String.format("aft:%s,dur:%d,cov:%d", schedule.getAfter(), schedule.getDuration(), schedule.getPgCovPct());
    }

    public static String versionedPolicyDetailsCompactRepr(PolicyDTO.VersionedPolicyDetails versionedPolicyDetails) {
        return String.format("version=%d,policy={%s}", versionedPolicyDetails.getVersion(), policyDetailsCompactRepr(versionedPolicyDetails.getPolicyDetails()));
    }
}
