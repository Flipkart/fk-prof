package fk.prof.userapi.http;

public final class UserapiApiPathConstants {

  private UserapiApiPathConstants() { }

  public static final String APPS = "/apps";
  public static final String CLUSTER_GIVEN_APPID = "/cluster/:appId";
  public static final String PROC_GIVEN_APPID_CLUSTERID = "/proc/:appId/:clusterId";
  public static final String PROFILES_GIVEN_APPID_CLUSTERID_PROCID = "/profiles/:appId/:clusterId/:procId";
  public static final String PROFILE_GIVEN_APPID_CLUSTERID_PROCID_WORKTYPE_TRACENAME = "/profile/:appId/:clusterId/:procId/cpu-sampling/:traceName";
  public static final String CALLERS_VIEW_FOR_CPU_SAMPLING = "/callers/:appId/:clusterId/:procId/cpu-sampling/:traceName";
  public static final String CALLEES_VIEW_FOR_CPU_SAMPLING = "/callees/:appId/:clusterId/:procId/cpu-sampling/:traceName";
  public static final String HEALTHCHECK = "/health";
}
