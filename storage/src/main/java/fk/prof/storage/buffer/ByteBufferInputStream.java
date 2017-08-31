package fk.prof.storage.buffer;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * InputStream wrapper for ByteBuffer.
 * @author gaurav.ashok
 */
public class ByteBufferInputStream extends InputStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(ByteBufferInputStream.class);
    private final GenericObjectPool<ByteBuffer> bufferPool;
    private final ByteBuffer buf;
    private boolean closed = false;

    public ByteBufferInputStream(GenericObjectPool<ByteBuffer> bufferPool, ByteBuffer buf) {
        this.buf = buf;
        this.bufferPool = bufferPool;
    }

    @Override
    public int read() throws IOException {
        if(buf.remaining() > 0) {
            return buf.get();
        }
        return -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if(buf.remaining() > 0) {
            int bytesToRead = Math.min(buf.remaining(), len);
            buf.get(b, off, bytesToRead);
            return bytesToRead;
        }
        return -1;
    }

    @Override
    public int available() throws IOException {
        int remaining = buf.remaining();
        // if there are no bytes remaining, its eof, so return -1.
        return remaining > 0 ? remaining : -1;
    }

    @Override
    public void close() throws IOException {
        LOGGER.debug("returning buffer to bufferPool");
        synchronized (this) {
            if(!closed) {
                bufferPool.returnObject(buf);
                closed = true;
            }
        }
    }
}
