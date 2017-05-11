package fk.prof.backend.http;

public final class ApiPathConstants {
  public static final String DELIMITER = "/";

  public static final String AGGREGATOR_POST_PROFILE = "/profile";

  public static final String LEADER_POST_LOAD = "/leader/load";
  public static final String LEADER_GET_WORK = "/leader/work";
  public static final String LEADER_POST_ASSOCIATION = "/leader/association";

  public static final String BACKEND_POST_ASSOCIATION = "/association";
  public static final String BACKEND_POST_POLL = "/poll";
  public static final String BACKEND_HEALTHCHECK = "/health";

  public static final String BACKEND_POLICIES = "/policies/";
  public static final String BACKEND_GET_POLICIES_GIVEN_APPID = "/policies/:appId";
  public static final String BACKEND_GET_POLICIES_GIVEN_APPID_CLUSTERID = "/policies/:appId/:clusterId";
  public static final String BACKEND_GET_POLICIES_GIVEN_APPID_CLUSTERID_PROCESS = "/policies/:appId/:clusterId/:process";
  public static final String BACKEND_PUT_POLICY_GIVEN_APPID_CLUSTERID_PROCESS = "/policies/:appId/:clusterId/:process";
  public static final String BACKEND_DELETE_POLICY_GIVEN_APPID_CLUSTERID_PROCESS = "/policies/:appId/:clusterId/:process";

  public static final String LEADER_POLICIES = "/leader/policies/";
  public static final String LEADER_GET_POLICIES_GIVEN_APPID = "/leader/policies/:appId";
  public static final String LEADER_GET_POLICIES_GIVEN_APPID_CLUSTERID = "/leader/policies/:appId/:clusterId";
  public static final String LEADER_GET_POLICIES_GIVEN_APPID_CLUSTERID_PROCESS = "/leader/policies/:appId/:clusterId/:process";
  public static final String LEADER_PUT_POLICY_GIVEN_APPID_CLUSTERID_PROCESS = "/leader/policies/:appId/:clusterId/:process";
  public static final String LEADER_DELETE_POLICY_GIVEN_APPID_CLUSTERID_PROCESS = "/leader/policies/:appId/:clusterId/:process";

  private ApiPathConstants() {
  }

}
