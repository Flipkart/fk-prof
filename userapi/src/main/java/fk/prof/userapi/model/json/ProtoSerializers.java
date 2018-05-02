package fk.prof.userapi.model.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import fk.prof.aggregation.proto.AggregatedProfileModel;

import java.io.IOException;

/**
 * @author gaurav.ashok
 */
public class ProtoSerializers {

    public static void registerSerializers(ObjectMapper om) {
        SimpleModule module = new SimpleModule("protobufSerializers", new Version(1, 0, 0, null, null, null));
        module.addSerializer(AggregatedProfileModel.FrameNode.class, new FrameNodeSerializer());
        module.addSerializer(AggregatedProfileModel.CPUSamplingNodeProps.class, new CpuSampleFrameNodePropsSerializer());
        module.addSerializer(AggregatedProfileModel.Header.class, new HeaderSerializer());
        module.addSerializer(AggregatedProfileModel.RecorderInfo.class, new RecorderInfoSerializer());
        module.addSerializer(AggregatedProfileModel.ProfileWorkInfo.class, new ProfileWorkInfoSerializer());
        module.addSerializer(AggregatedProfileModel.TraceCtxDetail.class, new TraceCtxDetailsSerializer());
        om.registerModule(module);
    }

    static class FrameNodeSerializer extends StdSerializer<AggregatedProfileModel.FrameNode> {

        public FrameNodeSerializer() {
            super(AggregatedProfileModel.FrameNode.class);
        }

        @Override
        public void serialize(AggregatedProfileModel.FrameNode value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartArray();
            gen.writeNumber(value.getMethodId());
            gen.writeNumber(value.getChildCount());
            gen.writeNumber(value.getLineNo());
            if(value.getCpuSamplingProps() != null) {
                JsonSerializer cpuSamplesPropsSerializer = serializers.findValueSerializer(AggregatedProfileModel.CPUSamplingNodeProps.class);
                cpuSamplesPropsSerializer.serialize(value.getCpuSamplingProps(), gen, serializers);
            }
            gen.writeEndArray();
        }
    }

    static class TraceCtxDetailsSerializer extends StdSerializer<AggregatedProfileModel.TraceCtxDetail> {

        public TraceCtxDetailsSerializer() {
            super(AggregatedProfileModel.TraceCtxDetail.class);
        }

        @Override
        public void serialize(AggregatedProfileModel.TraceCtxDetail value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeNumberField("trace_idx", value.getTraceIdx());
            gen.writeFieldName("props");
            gen.writeStartObject();
            gen.writeNumberField("samples", value.getSampleCount());
            gen.writeEndObject();
            gen.writeEndObject();
        }
    }

    static class CpuSampleFrameNodePropsSerializer extends StdSerializer<AggregatedProfileModel.CPUSamplingNodeProps> {

        public CpuSampleFrameNodePropsSerializer() {
            super(AggregatedProfileModel.CPUSamplingNodeProps.class);
        }

        @Override
        public void serialize(AggregatedProfileModel.CPUSamplingNodeProps value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartArray();
            gen.writeNumber(value.getOnStackSamples());
            gen.writeNumber(value.getOnCpuSamples());
            gen.writeEndArray();
        }
    }

    static class HeaderSerializer extends StdSerializer<AggregatedProfileModel.Header> {

        public HeaderSerializer() {
            super(AggregatedProfileModel.Header.class);
        }

        @Override
        public void serialize(AggregatedProfileModel.Header header, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("app_id", header.getAppId());
            gen.writeStringField("cluster_id", header.getClusterId());
            gen.writeStringField("proc_id", header.getProcId());
            gen.writeStringField("aggregation_startTime", header.getAggregationStartTime());
            gen.writeStringField("aggregation_end_time", header.getAggregationEndTime());
            if(header.hasWorkType()) {
                gen.writeStringField("work_type", header.getWorkType().name());
            }
            gen.writeEndObject();
        }
    }

    static class RecorderInfoSerializer extends StdSerializer<AggregatedProfileModel.RecorderInfo> {
        public RecorderInfoSerializer() {
            super(AggregatedProfileModel.RecorderInfo.class);
        }

        @Override
        public void serialize(AggregatedProfileModel.RecorderInfo value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("ip", value.getIp());
            gen.writeStringField("hostname", value.getHostname());
            gen.writeStringField("app_id", value.getAppId());
            gen.writeStringField("instance_group", value.getInstanceGroup());
            gen.writeStringField("cluster", value.getCluster());
            gen.writeStringField("instace_id", value.getInstanceId());
            gen.writeStringField("process_name", value.getProcessName());
            gen.writeStringField("vm_id", value.getVmId());
            gen.writeStringField("zone", value.getZone());
            gen.writeStringField("instance_type", value.getInstanceType());
            gen.writeEndObject();
        }
    }

    static class ProfileWorkInfoSerializer extends StdSerializer<AggregatedProfileModel.ProfileWorkInfo> {
        public ProfileWorkInfoSerializer() {
            super(AggregatedProfileModel.ProfileWorkInfo.class);
        }

        @Override
        public void serialize(AggregatedProfileModel.ProfileWorkInfo value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeNumberField("start_offset", value.getStartOffset());
            gen.writeNumberField("duration", value.getDuration());
            gen.writeNumberField("recorder_version", value.getRecorderVersion());

            if(value.hasRecorderInfo()) {
                gen.writeFieldName("recorder_info");
                JsonSerializer recorderInfoSerializer = serializers.findValueSerializer(AggregatedProfileModel.RecorderInfo.class);
                recorderInfoSerializer.serialize(value.getRecorderInfo(), gen, serializers);
            }

            gen.writeObjectFieldStart("sample_count");
            for(AggregatedProfileModel.ProfileWorkInfo.SampleCount sampleCount : value.getSampleCountList()) {
                gen.writeNumberField(sampleCount.getWorkType().name(), sampleCount.getSampleCount());
            }
            gen.writeEndObject();

            gen.writeStringField("status", value.getStatus().name());
            gen.writeArrayFieldStart("trace_coverage_map");
            for(AggregatedProfileModel.ProfileWorkInfo.TraceCtxToCoveragePctMap keyValue: value.getTraceCoverageMapList()) {
                gen.writeStartArray();
                gen.writeNumber(keyValue.getTraceCtxIdx());
                gen.writeNumber(keyValue.getCoveragePct());
                gen.writeEndArray();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
    }
}
