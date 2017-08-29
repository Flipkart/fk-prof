package fk.prof.userapi.http;

public final class UserapiApiPathConstants {
    private UserapiApiPathConstants() {
    }

    public static final String POLICIES_PREFIX = "/policies";
    public static final String POLICY_PREFIX = "/policy";
    public static final String META_PREFIX = "/meta";
    public static final String PROFILES_PREFIX = "/profiles";
    public static final String PROFILE_PREFIX = "/profile";

    public static final String APPS_PREFIX = "/apps";
    public static final String CLUSTERS_PREFIX = "/clusters";
    public static final String PROCS_PREFIX = "/procs";

    public static final String PROFILES_APPS = META_PREFIX + PROFILES_PREFIX + APPS_PREFIX;
    public static final String PROFILES_CLUSTERS_FOR_APP =  META_PREFIX + PROFILES_PREFIX + CLUSTERS_PREFIX + "/:appId";
    public static final String PROFILES_PROCS_FOR_APP_CLUSTER =  META_PREFIX + PROFILES_PREFIX + PROCS_PREFIX + "/:appId/:clusterId";
    public static final String PROFILES_FOR_APP_CLUSTER_PROC = PROFILES_PREFIX + "/:appId/:clusterId/:procName";
    public static final String CPU_SAMPLING_PROFILE_FOR_APP_CLUSTER_PROC_TRACE = PROFILE_PREFIX + "/:appId/:clusterId/:procName/cpu-sampling/:traceName";

    public static final String POLICIES_APPS = META_PREFIX + POLICIES_PREFIX + APPS_PREFIX;
    public static final String POLICIES_CLUSTERS_FOR_APP =  META_PREFIX  + POLICIES_PREFIX + CLUSTERS_PREFIX + "/:appId";
    public static final String POLICIES_PROCS_FOR_APP_CLUSTER = META_PREFIX + POLICIES_PREFIX + PROCS_PREFIX + "/:appId/:clusterId";
    public static final String GET_POLICY_FOR_APP_CLUSTER_PROC = POLICY_PREFIX + "/:appId/:clusterId/:procName";
    public static final String PUT_POLICY_FOR_APP_CLUSTER_PROC = POLICY_PREFIX + "/:appId/:clusterId/:procName";
    public static final String POST_POLICY_FOR_APP_CLUSTER_PROC = POLICY_PREFIX  + "/:appId/:clusterId/:procName";

    public static final String HEALTH_CHECK = "/health";

}
