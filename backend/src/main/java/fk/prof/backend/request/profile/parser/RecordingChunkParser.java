package fk.prof.backend.request.profile.parser;

import com.codahale.metrics.Histogram;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.request.CompositeByteBufInputStream;
import fk.prof.idl.Recording;

import java.io.IOException;
import java.util.zip.Adler32;

public class RecordingChunkParser {
  private Recording.RecordingChunk chunk = null;

  private Adler32 checksum = new Adler32();
  private boolean parsed = false;
  private int maxMessageSizeInBytes;
  private boolean endMarkerReceived = false;

  private MessageParser msgParser;

  public RecordingChunkParser(int maxMessageSizeInBytes, Histogram histChunkSize) {
    this.maxMessageSizeInBytes = maxMessageSizeInBytes;
    this.msgParser = new MessageParser(histChunkSize);
  }

  /**
   * Returns true if recording chunk has been read and checksum validated, false otherwise
   *
   * @return returns if recording chunk has been parsed or not
   */
  public boolean isParsed() {
    return this.parsed;
  }

  public boolean isEndMarkerReceived() {
    return this.endMarkerReceived;
  }

  /**
   * Returns {@link Recording.RecordingChunk} if {@link #isParsed()} is true, null otherwise
   *
   * @return
   */
  public Recording.RecordingChunk get() {
    return this.chunk;
  }

  /**
   * Resets internal fields of the parser
   * Note: If {@link #get()} is not performed before reset, previous parsed entry will be lost
   */
  public void reset() {
    this.chunk = null;
    this.parsed = false;
    this.checksum.reset();
  }

  /**
   * Reads buffer and updates internal state with parsed fields.
   * @param in
   */
  public void parse(CompositeByteBufInputStream in) throws AggregationFailure {
    try {
      if (chunk == null) {
        in.markAndDiscardRead();
        chunk = msgParser.readDelimited(Recording.RecordingChunk.parser(), in, maxMessageSizeInBytes, "RecordingChunk");
        if(chunk == null) {
          endMarkerReceived = true;
          return;
        }
        in.updateChecksumSinceMarked(checksum);
      }
      in.markAndDiscardRead();
      int checksumValue = msgParser.readRawVariantInt(in, "recordingChunkChecksumValue");
      if (checksumValue != ((int) checksum.getValue())) {
        throw new AggregationFailure("Checksum of recording chunk does not match");
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
      throw new AggregationFailure(e, true);
    }
  }
}
