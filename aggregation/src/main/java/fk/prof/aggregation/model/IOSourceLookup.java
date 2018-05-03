package fk.prof.aggregation.model;

import fk.prof.idl.Profile;
import fk.prof.idl.Recording;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IOSourceLookup {
  public final static int FILE_INVALID_ID = 0;
  public final static Profile.IOSource FILE_INVALID_INFO = Profile.IOSource.newBuilder().setFdType(Recording.FDType.file).setUri("Invalid File").build();
  public final static int FILE_NIO_INVALID_ID = 1;
  public final static Profile.IOSource FILE_NIO_INVALID_INFO = Profile.IOSource.newBuilder().setFdType(Recording.FDType.filenio).setUri("Invalid File").build();
  public final static int SOCKET_INVALID_ID = 2;
  public final static Profile.IOSource SOCKET_INVALID_INFO = Profile.IOSource.newBuilder().setFdType(Recording.FDType.socket).setUri("Invalid Socket").build();
  public final static int SOCKET_NIO_INVALID_ID = 3;
  public final static Profile.IOSource SOCKET_NIO_INVALID_INFO = Profile.IOSource.newBuilder().setFdType(Recording.FDType.socketnio).setUri("Invalid Socket").build();


  //Counter to generate io source ids in auto increment fashion
  private final AtomicInteger counter = new AtomicInteger(4);
  private final ConcurrentHashMap<Profile.IOSource, Integer> lookup = new ConcurrentHashMap<>();

  public IOSourceLookup() {
    lookup.put(FILE_INVALID_INFO, FILE_INVALID_ID);
    lookup.put(FILE_NIO_INVALID_INFO, FILE_NIO_INVALID_ID);
    lookup.put(SOCKET_INVALID_INFO, SOCKET_INVALID_ID);
    lookup.put(SOCKET_NIO_INVALID_INFO, SOCKET_NIO_INVALID_ID);
  }

  public Integer getOrAdd(Recording.FDInfo fdInfo) {
    Profile.IOSource.Builder builder = Profile.IOSource.newBuilder().setFdType(fdInfo.getFdType());
    if (fdInfo.hasFileInfo()) {
      builder.setUri(fdInfo.getFileInfo().getFilename());
    } else if(fdInfo.hasSocketInfo()) {
      builder.setUri(fdInfo.getSocketInfo().getAddress());
    }
    Profile.IOSource src = builder.build();
    return lookup.computeIfAbsent(src, (key -> counter.getAndIncrement()));
  }

  /**
   * Generates a reverse lookup array where array index corresponds to sourceId
   * We are assured of a 1:1 relationship between K and V because of an atomic counter being used to generate sequential lookup values.
   * Therefore, reverse lookup is modelled as an array
   *
   * @return indexed array where arr[idx] = io source and idx = corresponding source id
   * NOTE: Make the access private if not required outside post serialization is implemented
   */
  public Profile.IOSource[] generateReverseLookup() {
    Profile.IOSource[] reverseLookup = new Profile.IOSource[counter.get()];
    lookup.entrySet().forEach(entry -> reverseLookup[entry.getValue()] = entry.getKey());
    return reverseLookup;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof IOSourceLookup)) {
      return false;
    }

    IOSourceLookup other = (IOSourceLookup) o;
    return this.counter.get() == other.counter.get()
        && this.lookup.equals(other.lookup);
  }

  protected Profile.IOSources buildProto() {
    return Profile.IOSources.newBuilder().addAllIoSources(Arrays.asList(generateReverseLookup())).build();
  }

}
