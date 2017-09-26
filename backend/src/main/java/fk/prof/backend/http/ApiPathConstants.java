package fk.prof.backend.http;

public final class ApiPathConstants {
  private ApiPathConstants() {
  }

  public static final String LEADER_PREFIX = "/leader";
  public static final String POLICY_PREFIX = "/policy";
  public static final String APPS_PREFIX = "/apps";
  public static final String CLUSTERS_PREFIX = "/clusters";
  public static final String PROCS_PREFIX = "/procs";

  public static final String AGGREGATOR_POST_PROFILE = "/profile";

  public static final String BACKEND_POST_POLL = "/poll";
  public static final String BACKEND_HEALTHCHECK = "/health";
  public static final String BACKEND_POST_ASSOCIATION = "/association";
  public static final String BACKEND_GET_ASSOCIATIONS = "/associations";
  public static final String BACKEND_GET_APPS = APPS_PREFIX;
  public static final String BACKEND_GET_CLUSTERS_FOR_APP = CLUSTERS_PREFIX + "/:appId";
  public static final String BACKEND_GET_PROCS_FOR_APP_CLUSTER = PROCS_PREFIX + "/:appId/:clusterId";
  public static final String BACKEND_GET_POLICY_FOR_APP_CLUSTER_PROC = POLICY_PREFIX + "/:appId/:clusterId/:procName";
  public static final String BACKEND_PUT_POLICY_FOR_APP_CLUSTER_PROC = POLICY_PREFIX + "/:appId/:clusterId/:procName";
  public static final String BACKEND_POST_POLICY_FOR_APP_CLUSTER_PROC = POLICY_PREFIX + "/:appId/:clusterId/:procName";


  // TODO: ADMIN APIs carry great risk.
  // Should be used with caution and support some form of authentication in future.
  // Should be removed when they are not required anymore because of other features getting added
  public static final String LEADER_ADMIN_DELETE_ASSOCIATION = LEADER_PREFIX + "/admin/association";

  public static final String LEADER_POST_LOAD = LEADER_PREFIX + "/load";
  public static final String LEADER_GET_WORK = LEADER_PREFIX + "/work";
  public static final String LEADER_POST_ASSOCIATION = LEADER_PREFIX + "/association";
  public static final String LEADER_GET_ASSOCIATIONS = LEADER_PREFIX + "/associations";
  public static final String LEADER_GET_APPS = LEADER_PREFIX + APPS_PREFIX;
  public static final String LEADER_GET_CLUSTERS_FOR_APP = LEADER_PREFIX + CLUSTERS_PREFIX + "/:appId";
  public static final String LEADER_GET_PROCS_FOR_APP_CLUSTER = LEADER_PREFIX + PROCS_PREFIX + "/:appId/:clusterId";
  public static final String LEADER_GET_POLICY_FOR_APP_CLUSTER_PROC = LEADER_PREFIX + POLICY_PREFIX + "/:appId/:clusterId/:procName";
  public static final String LEADER_PUT_POLICY_FOR_APP_CLUSTER_PROC = LEADER_PREFIX + POLICY_PREFIX + "/:appId/:clusterId/:procName";
  public static final String LEADER_POST_POLICY_FOR_APP_CLUSTER_PROC = LEADER_PREFIX + POLICY_PREFIX + "/:appId/:clusterId/:procName";

}
