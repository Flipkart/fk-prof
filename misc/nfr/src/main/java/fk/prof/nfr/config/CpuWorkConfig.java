package fk.prof.nfr.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class CpuWorkConfig {

    public final Integer loadTypes = 3;

    // threads doing cpu intensive work
    @JsonProperty("threadCnt")
    public Integer totalThreadCnt;

    // ratio in which the 1 sec cpu time is roughly divided among all the load types.
    // eg: 0.33 0.33 0.34
    // or 0.5 0.5 0
    @JsonProperty("loadShare")
    public List<Float> loadShare;

    // the whole cpu load cycle will run for loadMultiplier seconds
    @JsonProperty("loadMultiplier")
    public Float loadMultiplier = 1.0f;

    // 3 * multiplier traces are created in total. multiplier for each load type.
    @JsonProperty("traceDuplicationMultiplier")
    public Integer traceDuplicationMultiplier = 1;

    @JsonProperty("perfCtxCodeCovrg")
    public Integer perfCtxCodeCoverage;

    // call tree fanout
    @JsonProperty("stacktraceFanout")
    public Integer stacktraceFanOut;

    @JsonProperty("stacktraceDepth")
    public Integer stacktraceDepth;
}
