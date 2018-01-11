package fk.prof.backend.util;

import com.google.protobuf.*;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.idl.Recorder;
import fk.prof.idl.WorkEntities;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;

public class ProtoUtil {
  public static AggregatedProfileModel.WorkType mapRecorderToAggregatorWorkType(WorkEntities.WorkType recorderWorkType) {
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
}
