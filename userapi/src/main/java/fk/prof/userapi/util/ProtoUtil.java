package fk.prof.userapi.util;

import com.google.protobuf.*;
import fk.prof.idl.Profile;
import fk.prof.idl.Recorder;
import fk.prof.idl.WorkEntities;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;

//TODO : Duplicate from backend, extract out in a common module
public class ProtoUtil {
  public static WorkEntities.WorkType mapRecorderToAggregatorWorkType(WorkEntities.WorkType recorderWorkType) {
    return WorkEntities.WorkType.forNumber(recorderWorkType.getNumber());
  }

  public static Profile.RecorderDetails mapToAggregatorRecorderDetails(Recorder.RecorderInfo r) {
    return Profile.RecorderDetails.newBuilder()
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
}
