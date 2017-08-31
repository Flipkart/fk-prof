package fk.prof.storage.buffer;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.FileNamingStrategy;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

/**
 * OutputStream implementation which is backed by a {@link AsyncStorage}.
 * Every {@code partSize} chunk of the bytes will be written to the storage.
 * The target path of the chunk will be decided by the {@link FileNamingStrategy}.
 *
 * @see StorageBackedInputStream
 * @author gaurav.ashok
 */
public class StorageBackedOutputStream extends OutputStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageBackedOutputStream.class);

    private AsyncStorage storage;
    private FileNamingStrategy fileNameStrategy;
    private GenericObjectPool<ByteBuffer> bufferPool;

    private int part;
    private ByteBuffer buf;

    private final Histogram histBytesWritten;
    private final Meter mtrWriteFailure;
    private final Timer tmrBuffPoolBorrow;
    private final Counter ctrBuffPoolFailures;

    /**
     * @param bufferPool pool from which the buffer will be borrowed for buffering.
     * @param storage AsyncStorage object.
     * @param fileNameStrategy decides the fileName from the part no.
     */
    public StorageBackedOutputStream(GenericObjectPool<ByteBuffer> bufferPool,
                                     AsyncStorage storage,
                                     FileNamingStrategy fileNameStrategy,
                                     Histogram histBytesWritten,
                                     Meter mtrWriteFailure,
                                     Timer tmrBuffPoolBorrow,
                                     Counter ctrBuffPoolFailures) {
        this.storage = storage;
        this.fileNameStrategy = fileNameStrategy;
        this.part = 0;
        this.bufferPool = bufferPool;
        this.buf = null;

        this.histBytesWritten = histBytesWritten;
        this.mtrWriteFailure = mtrWriteFailure;
        this.tmrBuffPoolBorrow = tmrBuffPoolBorrow;
        this.ctrBuffPoolFailures = ctrBuffPoolFailures;
    }

    @Override
    public void write(int b) throws IOException {
        if (buf == null || buf.remaining() == 0) {
            storeAndSwapBuffer();
        }
        buf.put((byte) b);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (len < 0) {
            throw new IllegalArgumentException();
        } else if (len == 0) {
            return;
        }

        if (buf == null) {
            // get a buffer from pool
            storeAndSwapBuffer();
        }

        int bytesToWrite = len;
        do {
            int minWriteSize = Math.min(bytesToWrite, buf.remaining());
            buf.put(b, off + (len - bytesToWrite), minWriteSize);
            bytesToWrite -= minWriteSize;

            if (buf.remaining() == 0) {
                storeAndSwapBuffer();
            }
        } while (bytesToWrite > 0);
    }

    private void storeAndSwapBuffer() throws IOException {
        if(buf != null) {
            writeBufToStorage();
            ++part;
        }

        try {
            buf = null; // get rid of the reference, in case the borrow fails
            try (Timer.Context context = tmrBuffPoolBorrow.time()) {
                if(LOGGER.isDebugEnabled()) {
                    LOGGER.debug("acquiring buffer for file: {}. bufferpool.active: {}", fileNameStrategy.getFileName(0), bufferPool.getNumActive());
                }
                buf = bufferPool.borrowObject();
            }
        }
        catch (NoSuchElementException | IllegalStateException e) {
            ctrBuffPoolFailures.inc();
            final String msg = "buffer pool is either closed or has no object to return";
            LOGGER.error(msg, e);
            throw new IOException(msg, e);
        }
        catch (Exception e) {
            ctrBuffPoolFailures.inc();
            LOGGER.error("Unexpected error while borrowing from bufferPool", e);
            throw new IOException(e);
        }
    }

    @Override
    public void flush() {
        // flush not supported
    }

    @Override
    public void close() throws IOException {
        if(buf != null) {
            if(buf.position() > 0) {
                writeBufToStorage();
            }
            else {
                if(LOGGER.isDebugEnabled()) {
                    LOGGER.debug("returning buffer on close: {}", fileNameStrategy.getFileName(0));
                }
                bufferPool.returnObject(buf);
            }
        }
    }

    private void writeBufToStorage() {
        long contentLength = buf.position();
        // prepare for reading
        buf.flip();
        storage.storeAsync(fileNameStrategy.getFileName(part),
            new ByteBufferInputStream(bufferPool, buf), contentLength)
            .whenCompleteAsync((v, th) -> {
                if(th != null) {
                    this.mtrWriteFailure.mark();
                    LOGGER.error("S3 putobject failed for file_strategy=" + fileNameStrategy, th);
                } else {
                    this.histBytesWritten.update(contentLength);
                }
            });
    }
}