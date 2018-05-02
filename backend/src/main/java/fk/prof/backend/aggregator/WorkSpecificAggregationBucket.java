package fk.prof.backend.aggregator;

import fk.prof.aggregation.FinalizableBuilder;
import fk.prof.aggregation.model.FinalizedWorkSpecificAggregationBucket;

public abstract class WorkSpecificAggregationBucket<T extends FinalizedWorkSpecificAggregationBucket> extends FinalizableBuilder<T> {
  /*This override is required because of type erasure in Java
    FinalizableBuilder erases type bound of "FinalizedWorkSpecificAggregationBucket" imposed in this class on T generic. This causes finalizeEntity to return object of class "Object"
    Having an override here ensures that finalizeEntity returns object of class "FinalizedWorkSpecificAggregationBucket"
   */
  @Override
  public T finalizeEntity() {
    return super.finalizeEntity();
  }
}
