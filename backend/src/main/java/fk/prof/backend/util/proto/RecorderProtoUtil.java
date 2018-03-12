package fk.prof.backend.util.proto;

import fk.prof.idl.Entities;
import fk.prof.idl.Recorder;
import fk.prof.idl.WorkEntities;

public class RecorderProtoUtil {

  public static Entities.ProcessGroup mapRecorderInfoToProcessGroup(Recorder.RecorderInfo recorderInfo) {
    return Entities.ProcessGroup.newBuilder()
        .setAppId(recorderInfo.getAppId())
        .setCluster(recorderInfo.getCluster())
        .setProcName(recorderInfo.getProcName())
        .build();
  }

  public static String processGroupCompactRepr(Entities.ProcessGroup processGroup) {
    return processGroup == null ? null : String.format("%s,%s,%s", processGroup.getAppId(), processGroup.getCluster(), processGroup.getProcName());
  }

  public static String assignedBackendCompactRepr(Recorder.AssignedBackend assignedBackend) {
    return assignedBackend == null ? null : String.format("%s,%s", assignedBackend.getHost(), assignedBackend.getPort());
  }

  public static String recorderInfoCompactRepr(Recorder.RecorderInfo recorderInfo) {
    if(recorderInfo == null) {
      return null;
    }
    return "ip=" + recorderInfo.getIp() +
        ", host=" + recorderInfo.getHostname() +
        ", cluster=" + recorderInfo.getCluster() +
        ", proc=" + recorderInfo.getProcName() +
        ", rec_version=" + recorderInfo.getRecorderVersion();
  }

  public static String workResponseCompactRepr(WorkEntities.WorkResponse workResponse) {
    if(workResponse == null) {
      return null;
    }
    return "work_id=" + workResponse.getWorkId() +
        ", state=" + workResponse.getWorkState() +
        ", result=" + workResponse.getWorkResult() +
        ", elapsed=" + workResponse.getElapsedTime();
  }

  public static String pollReqCompactRepr(Recorder.PollReq pollReq) {
    if(pollReq == null) {
      return null;
    }
    return "rec_info: " + recorderInfoCompactRepr(pollReq.getRecorderInfo()) +
        ", work_resp: " + workResponseCompactRepr(pollReq.getWorkLastIssued());
  }

  public static String pollResCompactRepr(Recorder.PollRes pollRes) {
    if(pollRes == null) {
      return null;
    }
    if(pollRes.getAssignment() == null) {
      return "ctrl_id=" + pollRes.getControllerId() + ", empty work assignment";
    }
    return "ctrl_id=" + pollRes.getControllerId() +
        ", work_id=" + pollRes.getAssignment().getWorkId() +
        ", issue=" + pollRes.getAssignment().getIssueTime() +
        ", dur=" + pollRes.getAssignment().getDuration() +
        ", delay=" + pollRes.getAssignment().getDelay();
  }

  private static WorkEntities.WorkType translateWorkTypeFromBackendDTO(WorkEntities.WorkType backendDTOWorkType) {
    return WorkEntities.WorkType.forNumber(backendDTOWorkType.getNumber());
  }

  private static WorkEntities.CpuSampleWork translateCpuSampleWorkFromBackendDTO(WorkEntities.CpuSampleWork backendDTOCpuSampleWork) {
    if(backendDTOCpuSampleWork == null) {
      return null;
    }
    return WorkEntities.CpuSampleWork.newBuilder()
        .setFrequency(backendDTOCpuSampleWork.getFrequency())
        .setMaxFrames(backendDTOCpuSampleWork.getMaxFrames())
        .build();
  }
}
