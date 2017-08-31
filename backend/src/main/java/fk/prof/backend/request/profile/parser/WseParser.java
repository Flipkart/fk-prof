package fk.prof.backend.request.profile.parser;

import com.codahale.metrics.Histogram;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.request.CompositeByteBufInputStream;
import recording.Recorder;

import java.io.IOException;
import java.util.zip.Adler32;

public class WseParser {
  private Recorder.Wse wse = null;

  private Adler32 wseChecksum = new Adler32();
  private boolean wseParsed = false;
  private int maxMessageSizeInBytes;
  private boolean endMarkerReceived = false;

  private MessageParser msgParser;

  public WseParser(int maxMessageSizeInBytes, Histogram histWseSize) {
    this.maxMessageSizeInBytes = maxMessageSizeInBytes;
    this.msgParser = new MessageParser(histWseSize);
  }

  /**
   * Returns true if wse has been read and checksum validated, false otherwise
   *
   * @return returns if wse has been parsed or not
   */
  public boolean isParsed() {
    return this.wseParsed;
  }

  public boolean isEndMarkerReceived() {
    return this.endMarkerReceived;
  }

  /**
   * Returns {@link Recorder.Wse} if {@link #isParsed()} is true, null otherwise
   *
   * @return
   */
  public Recorder.Wse get() {
    return this.wse;
  }

  /**
   * Resets internal fields of the parser
   * Note: If {@link #get()} is not performed before reset, previous parsed entry will be lost
   */
  public void reset() {
    this.wse = null;
    this.wseParsed = false;
    this.wseChecksum.reset();
  }

  /**
   * Reads buffer and updates internal state with parsed fields.
   * @param in
   */
  public void parse(CompositeByteBufInputStream in) throws AggregationFailure {
    try {
      if (wse == null) {
        in.markAndDiscardRead();
        wse = msgParser.readDelimited(Recorder.Wse.parser(), in, maxMessageSizeInBytes, "WSE");
        if(wse == null) {
          endMarkerReceived = true;
          return;
        }
        in.updateChecksumSinceMarked(wseChecksum);
      }
      in.markAndDiscardRead();
      int checksumValue = msgParser.readRawVariantInt(in, "wseChecksumValue");
      if (checksumValue != ((int) wseChecksum.getValue())) {
        throw new AggregationFailure("Checksum of wse does not match");
      }
      wseParsed = true;
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
