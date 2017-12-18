package fk.prof.userapi.util;

import com.google.protobuf.*;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vertx.core.buffer.Buffer;
import recording.Recorder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;

//TODO : Duplicate from backend, extract out in a common module
public class ProtoUtil {
  public static AggregatedProfileModel.WorkType mapRecorderToAggregatorWorkType(Recorder.WorkType recorderWorkType) {
    return AggregatedProfileModel.WorkType.forNumber(recorderWorkType.getNumber());
  }

  public static AggregatedProfileModel.RecorderInfo mapToAggregatorRecorderInfo(Recorder.RecorderInfo r) {
    return AggregatedProfileModel.RecorderInfo.newBuilder()
            .setAppId(r.getAppId())
            .setCluster(r.getCluster())
            .setProcessName(r.getProcName())
            .setHostname(r.getHostname())
            .setInstanceGroup(r.getInstanceGrp())
            .setInstanceId(r.getInstanceId())
            .setInstanceType(r.getInstanceType())
            .setIp(r.getIp())
            .setVmId(r.getVmId())
            .setZone(r.getZone()).build();
  }

  //Avoids double byte copy to create a vertx buffer
  public static Buffer buildBufferFromProto(AbstractMessage message) throws IOException {
    int serializedSize = message.getSerializedSize();
    ByteBuf byteBuf = Unpooled.buffer(serializedSize, Integer.MAX_VALUE);
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(byteBuf.array());
    message.writeTo(codedOutputStream);
    byteBuf.writerIndex(serializedSize);
    return Buffer.buffer(byteBuf);
  }

  //Proto parser operates directly on underlying byte array, avoids byte copy
  public static <T extends AbstractMessage> T buildProtoFromBuffer(Parser<T> parser, Buffer buffer)
      throws InvalidProtocolBufferException {
    return parser.parseFrom(CodedInputStream.newInstance(buffer.getByteBuf().nioBuffer()));
  }

  public static int readVariantInt32(InputStream in) throws IOException {
      int firstByte = in.read();
      if(firstByte == -1) {
          throw new EOFException("Expecting variantInt32");
      }
      return CodedInputStream.readRawVarint32(firstByte, in);
  }

  public static <T extends AbstractMessage> T buildProtoFromCheckedInputStream(Parser<T> parser, CheckedInputStream cin, String tag) throws IOException {
      Checksum checksum = cin.getChecksum();

      checksum.reset();
      T msg = parser.parseDelimitedFrom(cin);

      int chksmValue = (int)checksum.getValue();
      int expectedChksmValue = readVariantInt32(cin);

      assert chksmValue == expectedChksmValue : "Checksum did not match for " + tag;

      return msg;
  }
}
