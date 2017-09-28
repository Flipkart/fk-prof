package fk.prof.backend.http;

public final class ApiPathConstants {
  private ApiPathConstants() {
  }

  public static final String AGGREGATOR_POST_PROFILE = "/profile";

  public static final String LEADER_POST_LOAD = "/leader/load";
  public static final String LEADER_GET_WORK = "/leader/work";
  public static final String LEADER_POST_ASSOCIATION = "/leader/association";
  public static final String LEADER_POST_POLICY = "/leader/policy";
  public static final String LEADER_GET_ASSOCIATIONS = "/leader/associations";

  public static final String BACKEND_POST_ASSOCIATION = "/association";
  public static final String BACKEND_GET_ASSOCIATIONS = "/associations";
  public static final String BACKEND_POST_POLL = "/poll";
  public static final String BACKEND_HEALTHCHECK = "/health";
}
