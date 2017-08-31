package fk.prof.backend.request.profile.parser;

import com.codahale.metrics.Histogram;
import com.google.common.io.ByteStreams;
import com.google.protobuf.*;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.request.CompositeByteBufInputStream;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;

/**
 * @author gaurav.ashok
 */
public class MessageParser {

    private static Logger logger = LoggerFactory.getLogger(MessageParser.class);
    private Histogram histMsgSize;

    public MessageParser(Histogram histMsgSize) {
        this.histMsgSize = histMsgSize;
    }

    private static final int MAX_VARINT32_BYTES = 10;

    /**
     * Reads protobuf varint32 from input stream and returns the value
     * Throws UnexpectedEOFException if enough bytes are not available for reading varint32
     * THrows AggregationFailure if error reading varint32 provided that enough bytes were available
     * @param in
     * @param tag
     * @return
     * @throws IOException
     */
    public int readRawVariantInt(CompositeByteBufInputStream in, String tag) throws IOException {
        int bytesAvailable = in.available();
        int firstByte = in.read();
        if (firstByte == -1) {
            throw new UnexpectedEOFException();
        }

        try {
            return CodedInputStream.readRawVarint32(firstByte, in);
        }
        catch (InvalidProtocolBufferException e) {
            if(bytesAvailable > MAX_VARINT32_BYTES) {
                throw new AggregationFailure("Error while parsing " + tag, e);
            }
            throw new UnexpectedEOFException();
        }
    }

    /**
     * Reads length followed by protobuf_message from input stream and returns the read protobuf message
     * Returns null if length is zero
     * Throws UnexpectedEOFException if enough bytes are not available in stream for reading length
     * Throws UnexpectedEOFException if bytes in stream for reading message are less than length
     * Throws AggregationFailure if error reading length or message provided enough bytes were available
     * Throws AggregationFailure if length of protobuf message is more than max allowed length
     * @param parser
     * @param in
     * @param maxMessageSize
     * @param tag
     * @param <T>
     * @return
     * @throws IOException
     */
    public <T extends AbstractMessage> T readDelimited(Parser<T> parser, CompositeByteBufInputStream in, int maxMessageSize, String tag) throws IOException {
        try {
            int msgSize = readRawVariantInt(in, tag + ":size");
            histMsgSize.update(msgSize);

            if(msgSize == 0) {
                return null;
            }
            if(msgSize > maxMessageSize) {
                String errMsg = "invalid length for " + tag + ". msgSize: " + msgSize + ". maxLimit: " + maxMessageSize;
                logger.error(errMsg);
                throw new AggregationFailure(errMsg);
            }

            if(in.available() >= msgSize) {
                return parser.parseFrom(ByteStreams.limit(in, msgSize));
            }
            else {
                throw new UnexpectedEOFException();
            }
        }
        catch (InvalidProtocolBufferException e) {
            throw new AggregationFailure("Error while parsing " + tag, e);
        }
    }
}
