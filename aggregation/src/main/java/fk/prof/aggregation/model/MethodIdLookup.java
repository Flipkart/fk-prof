package fk.prof.aggregation.model;

import fk.prof.idl.Profile;
import fk.prof.idl.Profile.*;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MethodIdLookup {
  //0 is reserved for placeholder global root method of all stack-trace
  public final static int GLOBAL_ROOT_METHOD_ID = 0;
  public final static String GLOBAL_ROOT_METHOD_SIGNATURE = "~ ROOT ~.()";
  //1 is reserved for placeholder unclassifiable root method of all incomplete stack-traces
  public final static int UNCLASSIFIABLE_ROOT_METHOD_ID = 1;
  public final static String UNCLASSIFIABLE_ROOT_METHOD_SIGNATURE = "~ UNCLASSIFIABLE ~.()";
  public final static int DEFAULT_LINE_NUMBER = 0;

  //Counter to generate method ids in auto increment fashion
  private final AtomicInteger counter = new AtomicInteger(2);
  private final ConcurrentHashMap<String, Integer> lookup = new ConcurrentHashMap<>();

  public MethodIdLookup() {
    lookup.put(GLOBAL_ROOT_METHOD_SIGNATURE, GLOBAL_ROOT_METHOD_ID);
    lookup.put(UNCLASSIFIABLE_ROOT_METHOD_SIGNATURE, UNCLASSIFIABLE_ROOT_METHOD_ID);
  }

  public Integer getOrAdd(String methodSignature) {
    return lookup.computeIfAbsent(methodSignature, (key -> counter.getAndIncrement()));
  }

  /**
   * Generates a reverse lookup array where array index corresponds to methodId
   * We are assured of a 1:1 relationship between K and V because of an atomic counter being used to generate sequential lookup values.
   * Therefore, reverse lookup is modelled as an array
   *
   * @return indexed array where arr[idx] = method signature and idx = corresponding method id
   * NOTE: Make the access private if not required outside post serialization is implemented
   */
  public String[] generateReverseLookup() {
    String[] reverseLookup = new String[counter.get()];
    lookup.entrySet().forEach(entry -> reverseLookup[entry.getValue()] = entry.getKey());
    return reverseLookup;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof MethodIdLookup)) {
      return false;
    }

    MethodIdLookup other = (MethodIdLookup) o;
    return this.counter.get() == other.counter.get()
        && this.lookup.equals(other.lookup);
  }

  protected Profile.MethodLookUp buildMethodIdLookupProto() {
    return Profile.MethodLookUp.newBuilder().addAllFqdn(Arrays.asList(generateReverseLookup())).build();
  }
}
