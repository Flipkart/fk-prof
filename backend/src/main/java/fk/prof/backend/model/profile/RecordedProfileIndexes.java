package fk.prof.backend.model.profile;

import com.koloboke.collect.map.hash.HashIntObjMap;
import com.koloboke.collect.map.hash.HashIntObjMaps;
import com.koloboke.collect.map.hash.HashLongObjMap;
import com.koloboke.collect.map.hash.HashLongObjMaps;
import fk.prof.idl.Recording;

import java.util.List;

public class RecordedProfileIndexes {
  private final HashLongObjMap<String> methodLookup = HashLongObjMaps.newUpdatableMap();
  private final HashIntObjMap<String> traceLookup = HashIntObjMaps.newUpdatableMap();
  private final HashIntObjMap<Recording.FDInfo> fdLookup = HashIntObjMaps.newUpdatableMap();

  public String getMethod(long methodId) {
    return methodLookup.get(methodId);
  }

  public String getTrace(int traceId) {
    return traceLookup.get(traceId);
  }

  public void update(Recording.IndexedData indexedData) {
    updateMethodIndex(indexedData.getMethodInfoList());
    updateTraceIndex(indexedData.getTraceCtxList());
    updateFDIndex(indexedData.getFdInfoList());
  }

  private void updateMethodIndex(List<Recording.MethodInfo> methods) {
    if (methods != null) {
      for (Recording.MethodInfo methodInfo : methods) {
        methodLookup.put(methodInfo.getMethodId(),
            methodInfo.getClassFqdn() + "#" + methodInfo.getMethodName() + " " + methodInfo.getSignature());
      }
    }
  }

  private void updateTraceIndex(List<Recording.TraceContext> traces) {
    if (traces != null) {
      for (Recording.TraceContext traceContext : traces) {
        traceLookup.put(traceContext.getTraceId(), traceContext.getTraceName());
      }
    }
  }

  private void updateFDIndex(List<Recording.FDInfo> fds) {
    if(fds != null) {
      for (Recording.FDInfo fdInfo: fds) {
        fdLookup.put(fdInfo.getId(), fdInfo);
      }
    }
  }

}
