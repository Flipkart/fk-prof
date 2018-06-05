package fk.prof.nfr.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ThreadSpawnerConfig {

    @JsonProperty("threadCnt")
    public Integer threadCnt;

    @JsonProperty("threadSleepDuration")
    public Integer threadSleepDuration;
}
