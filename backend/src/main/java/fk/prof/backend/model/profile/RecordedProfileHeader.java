package fk.prof.backend.model.profile;

import fk.prof.idl.Recording;

public class RecordedProfileHeader {

  private final int encodingVersion;
  private final Recording.RecordingHeader recordingHeader;

  public RecordedProfileHeader(int encodingVersion, Recording.RecordingHeader recordingHeader) {
    this.encodingVersion = encodingVersion;
    this.recordingHeader = recordingHeader;
  }

  public int getEncodingVersion() {
    return encodingVersion;
  }

  public Recording.RecordingHeader getRecordingHeader() {
    return recordingHeader;
  }

}
