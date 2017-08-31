package fk.prof.backend.request.profile;

import com.codahale.metrics.*;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.aggregator.AggregationWindow;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.exception.HttpFailure;
import fk.prof.backend.http.HttpHelper;
import fk.prof.backend.model.profile.RecordedProfileHeader;
import fk.prof.backend.model.profile.RecordedProfileIndexes;
import fk.prof.backend.request.CompositeByteBufInputStream;
import fk.prof.backend.request.profile.parser.RecordedProfileHeaderParser;
import fk.prof.backend.request.profile.parser.WseParser;
import fk.prof.backend.model.aggregation.AggregationWindowDiscoveryContext;
import fk.prof.metrics.MetricName;
import fk.prof.metrics.ProcessGroupTag;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import recording.Recorder;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

public class RecordedProfileProcessor implements Handler<Buffer> {
  private static Logger logger = LoggerFactory.getLogger(RecordedProfileProcessor.class);

  private final RoutingContext context;
  private final AggregationWindowDiscoveryContext aggregationWindowDiscoveryContext;
  private final ISingleProcessingOfProfileGate singleProcessingOfProfileGate;
  private final RecordedProfileHeaderParser headerParser;
  private final WseParser wseParser;
  private final CompositeByteBufInputStream inputStream;
  private final RecordedProfileIndexes indexes = new RecordedProfileIndexes();

  private LocalDateTime startedAt = null;
  private RecordedProfileHeader header = null;
  private long workId = 0;
  private AggregationWindow aggregationWindow = null;

  private boolean errored = false;
  private Long chunkReceivedTime = null;

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private Histogram histChunkSize;
  private Timer tmrChunkIdle;
  private Meter mtrChunkBytes, mtrPayloadInvalid, mtrPayloadCorrupt;
  private Histogram histWseSize, histHeaderSize;
  private final Counter ctrAggrWinMiss = metricRegistry.counter(MetricName.Profile_Window_Miss.get());

  public RecordedProfileProcessor(RoutingContext context,
                                  AggregationWindowDiscoveryContext aggregationWindowDiscoveryContext,
                                  ISingleProcessingOfProfileGate singleProcessingOfProfileGate,
                                  int maxAllowedBytesForRecordingHeader,
                                  int maxAllowedBytesForWse) {
    this.context = context;
    this.aggregationWindowDiscoveryContext = aggregationWindowDiscoveryContext;
    this.singleProcessingOfProfileGate = singleProcessingOfProfileGate;
    this.inputStream = new CompositeByteBufInputStream();
    setupMetrics(ProcessGroupTag.EMPTY);
    this.wseParser = new WseParser(maxAllowedBytesForWse, histWseSize);
    this.headerParser = new RecordedProfileHeaderParser(maxAllowedBytesForRecordingHeader, histHeaderSize);
  }

  @Override
  public void handle(Buffer requestBuffer) {
    try {
//      try { logger.debug(String.format("buffer=%d, chunk=%d", inputStream.available(), requestBuffer.length())); } catch (Exception ex) {}
      long currentTime = System.nanoTime();
      if (chunkReceivedTime != null) {
        tmrChunkIdle.update(currentTime - chunkReceivedTime, TimeUnit.NANOSECONDS);
      }
      chunkReceivedTime = currentTime;

      if (!context.response().ended()) {
        process(requestBuffer);
      }
    } catch (Exception ex) {
      HttpFailure httpFailure = HttpFailure.failure(ex);
      HttpHelper.handleFailure(context, httpFailure);
    } finally {
      histChunkSize.update(requestBuffer.length());
      mtrChunkBytes.mark(requestBuffer.length());
    }
  }

  /**
   * Returns true if header has been successfully parsed to retrieve aggregation window and no wse entry log is processed partially
   *
   * @return processed a valid recorded profile object or not
   */
  public boolean isProcessed() {
    return aggregationWindow != null && wseParser.isEndMarkerReceived();
  }

  /**
   * If parsing was successful, marks the profile as corrupt if errored, completed/retried if processed, incomplete otherwise
   *
   * @throws AggregationFailure
   */
  public void close() throws AggregationFailure {
    try {
      inputStream.close();
      // Check for errored before checking for processed.
      // Profile can be corrupt(errored) even if processed returns true if more data is sent by client after server has received end marker
      if (errored) {
        mtrPayloadCorrupt.mark();
        if (aggregationWindow != null) {
          aggregationWindow.abandonProfileAsCorrupt(workId);
        }
      } else {
        if (isProcessed()) {
          aggregationWindow.completeProfile(workId);
        } else {
          mtrPayloadInvalid.mark();
          if (aggregationWindow != null) {
            aggregationWindow.abandonProfileAsIncomplete(workId);
          }
        }
      }
    } catch (IOException ex) {
      throw new AggregationFailure(ex, true);
    } finally {
      singleProcessingOfProfileGate.finish(workId);
    }
  }

  @Override
  public String toString() {
    return "work_id=" + workId + ", window={" + aggregationWindow + "}, errored=" + errored + ", processed=" + isProcessed();
  }

  /**
   * Reads buffer and updates internal state with parsed fields.
   * Aggregates the parsed entries in appropriate aggregation window
   * Returns the starting unread position in outputstream
   *
   * @param requestBuffer
   */
  private void process(Buffer requestBuffer) {
    inputStream.accept(requestBuffer.getByteBuf());

    if (startedAt == null) {
      startedAt = LocalDateTime.now(Clock.systemUTC());
    }

    try {
      if(isProcessed()) {
        throw new AggregationFailure("Cannot accept more data after receiving end marker");
      }

      if (aggregationWindow == null) {
        headerParser.parse(inputStream);

        if (headerParser.isParsed()) {
          header = headerParser.get();
          workId = header.getRecordingHeader().getWorkAssignment().getWorkId();

          singleProcessingOfProfileGate.accept(workId);
          aggregationWindow = aggregationWindowDiscoveryContext.getAssociatedAggregationWindow(workId);
          if (aggregationWindow == null) {
            ctrAggrWinMiss.inc();
            throw new AggregationFailure(String.format("workId=%d not found, cannot continue receiving associated profile",
                workId));
          }

          setupMetrics(aggregationWindow.getProcessGroupTag());
          aggregationWindow.startProfile(workId, header.getRecordingHeader().getRecorderVersion(), startedAt);
          logger.info(String.format("Profile aggregation started for work_id=%d started_at=%s",
              workId, startedAt.toString()));
        }
      }

      if (aggregationWindow != null) {
        while (inputStream.available() > 0) {
          wseParser.parse(inputStream);
          if(wseParser.isEndMarkerReceived()) {
            return;
          } else if (wseParser.isParsed()) {
            Recorder.Wse wse = wseParser.get();
            processWse(wse);
            wseParser.reset();
          } else {
            break;
          }
        }
      }
    } catch (AggregationFailure ex) {
      errored = true;
      throw ex;
    } catch (Exception ex) {
      errored = true;
      throw new AggregationFailure(ex, true);
    }
  }

  private void processWse(Recorder.Wse wse) throws AggregationFailure {
    indexes.update(wse.getIndexedData());
    aggregationWindow.updateWorkInfoWithWSE(workId, wse);
    aggregationWindow.aggregate(wse, indexes);
  }

  private void setupMetrics(ProcessGroupTag processGroupTag) {
    String processGroupTagStr = processGroupTag.toString();
    this.histChunkSize = metricRegistry.histogram(MetricRegistry.name(MetricName.Profile_Chunk_Size.get(), processGroupTagStr));
    this.tmrChunkIdle = metricRegistry.timer(MetricRegistry.name(MetricName.Profile_Chunk_Idle.get(), processGroupTagStr));
    this.mtrChunkBytes = metricRegistry.meter(MetricRegistry.name(MetricName.Profile_Chunk_Bytes.get(), processGroupTagStr));
    this.mtrPayloadInvalid = metricRegistry.meter(MetricRegistry.name(MetricName.Profile_Payload_Invalid.get(), processGroupTagStr));
    this.mtrPayloadCorrupt = metricRegistry.meter(MetricRegistry.name(MetricName.Profile_Payload_Corrupt.get(), processGroupTagStr));
    this.histWseSize = metricRegistry.histogram(MetricRegistry.name(MetricName.Profile_Wse_Size.get(), processGroupTagStr));
    this.histHeaderSize = metricRegistry.histogram(MetricRegistry.name(MetricName.Profile_Header_Size.get(), processGroupTagStr));
  }
}
