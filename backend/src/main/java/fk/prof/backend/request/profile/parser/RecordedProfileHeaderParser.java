package fk.prof.backend.request.profile.parser;

import com.codahale.metrics.Histogram;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.model.profile.RecordedProfileHeader;
import fk.prof.backend.request.CompositeByteBufInputStream;
import recording.Recorder;

import java.io.IOException;
import java.util.zip.Adler32;

public class RecordedProfileHeaderParser {
  private int encodingVersion;
  private Recorder.RecordingHeader recordingHeader = null;

  private Adler32 checksum = new Adler32();
  private boolean parsed = false;
  private int maxMessageSizeInBytes;
  private MessageParser msgParser;

  public RecordedProfileHeaderParser(int maxMessageSizeInBytes, Histogram histHeaderSize) {
    this.maxMessageSizeInBytes = maxMessageSizeInBytes;
    this.msgParser = new MessageParser(histHeaderSize);
  }

  /**
   * Returns true if all fields of header have been read and checksum validated, false otherwise
   *
   * @return returns if header has been parsed or not
   */
  public boolean isParsed() {
    return this.parsed;
  }

  /**
   * Returns {@link RecordedProfileHeader} if {@link #isParsed()} is true, null otherwise
   *
   * @return
   */
  public RecordedProfileHeader get() {
    if (!this.parsed) {
      return null;
    }
    return new RecordedProfileHeader(this.encodingVersion, this.recordingHeader);
  }

  /**
   * Reads buffer and updates internal state with parsed fields.
   * @param in
   */
  public void parse(CompositeByteBufInputStream in) throws AggregationFailure {
    try {
      if(recordingHeader == null) {
        in.markAndDiscardRead();
        encodingVersion = msgParser.readRawVariantInt(in, "encodingVersion");
        recordingHeader = msgParser.readDelimited(Recorder.RecordingHeader.parser(), in, maxMessageSizeInBytes, "recording header");
        in.updateChecksumSinceMarked(checksum);
      }
      in.markAndDiscardRead();
      int checksumValue = msgParser.readRawVariantInt(in, "headerChecksumValue");
      if(checksumValue != ((int)checksum.getValue())) {
        throw new AggregationFailure("Checksum of header does not match");
      }
      parsed = true;
    }
    catch (UnexpectedEOFException e) {
      try {
        in.resetMark();
      }
      catch (IOException resetEx) {
        throw new AggregationFailure(resetEx);
      }
    }
    catch (IOException e) {
      throw new AggregationFailure(e);
    }
  }
}
