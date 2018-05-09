package fk.prof.nfr.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class IoWorkConfig {

    @JsonProperty("nioThreadCnt")
    public Integer nioThreadCnt;

    @JsonProperty("workerThreadCnt")
    public Integer workerThreadCnt;

    @JsonProperty("driverRequestsInFlight")
    public Integer driverRequestsInFlight;
}
