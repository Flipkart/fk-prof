package fk.prof.userapi;

/**
 * Base interface for the objects that can be cached.
 * Created by gaurav.ashok on 21/06/17.
 */
public interface Cacheable {
    /* Adding a default impl here to avoid adding the default impl in every implementing class.
    * Later with smart caching strategy, implementing classes will return proper utilization weights.
    */
    default int getUtilizationWeight() {
        return 1;
    }
}
