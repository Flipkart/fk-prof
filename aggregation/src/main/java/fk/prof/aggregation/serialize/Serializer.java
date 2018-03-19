package fk.prof.aggregation.serialize;

import com.google.protobuf.AbstractMessage;
import com.google.protobuf.CodedOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

/**
 * Utility methods for serializers.
 * @author gaurav.ashok
 */
public interface Serializer {

    void serialize(OutputStream out) throws IOException;

    static void writeVariantInt32(int value, OutputStream os) throws IOException {
        byte[] bytes = new byte[8];

        CodedOutputStream cout = CodedOutputStream.newInstance(bytes);
        cout.writeUInt32NoTag(value);
        cout.flush();

        os.write(bytes, 0, cout.getTotalBytesWritten());
    }

    static void writeCheckedDelimited(AbstractMessage msg, CheckedOutputStream out) throws IOException {
        Checksum checksum = out.getChecksum();
        checksum.reset();

        msg.writeDelimitedTo(out);
        writeVariantInt32((int)checksum.getValue(), out);
    }
}
