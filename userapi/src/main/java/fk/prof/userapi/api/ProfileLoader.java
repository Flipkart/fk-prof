package fk.prof.userapi.api;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.AggregationWindowSummary;

import java.io.IOException;

/**
 * Interface to aggregated profiles loading.
 */
public interface ProfileLoader {

    AggregatedProfileInfo load(AggregatedProfileNamingStrategy fileName) throws IOException;

    AggregationWindowSummary loadSummary(AggregatedProfileNamingStrategy fileName) throws IOException;
}
