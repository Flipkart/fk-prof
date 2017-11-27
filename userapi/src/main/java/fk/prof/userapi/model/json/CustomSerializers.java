package fk.prof.userapi.model.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import fk.prof.aggregation.proto.AggregatedProfileModel.FrameNode;
import fk.prof.userapi.model.Tree;
import fk.prof.userapi.model.tree.IndexedTreeNode;
import fk.prof.userapi.model.tree.TreeViewResponse;
import fk.prof.userapi.model.tree.TreeViewResponse.CpuSampleCalleesTreeViewResponse;
import fk.prof.userapi.model.tree.TreeViewResponse.CpuSampleCallersTreeViewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;


/**
 * Created by gaurav.ashok on 07/08/17.
 */
public class CustomSerializers {
    private static final Logger logger = LoggerFactory.getLogger(CustomSerializers.class);

    public static void registerSerializers(ObjectMapper om) {
        SimpleModule module = new SimpleModule("customSerializers", new Version(1, 0, 0, null, null, null));
        module.addSerializer(CpuSampleCallersTreeViewResponse.class, new TreeViewResponseSerializer(new IndexedNodeSerializer(new ProtoSerializers.CpuSampleFrameNodeWithStackSampleSerializer())));
        module.addSerializer(CpuSampleCalleesTreeViewResponse.class, new TreeViewResponseSerializer(new IndexedNodeSerializer(new ProtoSerializers.CpuSampleFrameNodeWithCpuSampleSerializer())));
        om.registerModule(module);
    }

    static class IndexedNodeSerializer extends StdSerializer<IndexedTreeNode> {

        private StdSerializer dataSerializer;

        IndexedNodeSerializer(StdSerializer dataSerializer) {
            super(IndexedTreeNode.class);
            this.dataSerializer = dataSerializer;
        }

        @Override
        public void serialize(IndexedTreeNode indexedTreeNode, JsonGenerator gen, SerializerProvider serializers) {
            indexedTreeNode.visit(new IndexedTreeNodeVisitor(dataSerializer, gen, serializers));
        }
    }

    static class IndexedTreeNodeVisitor implements Tree.Visitor<IndexedTreeNode> {
        private final StdSerializer dataSerializer;
        private final JsonGenerator gen;
        private final SerializerProvider serializers;

        IndexedTreeNodeVisitor(StdSerializer dataSerializer, JsonGenerator gen, SerializerProvider serializers) {
            this.dataSerializer = dataSerializer;
            this.gen = gen;
            this.serializers = serializers;
        }

        @Override
        public void preVisit(IndexedTreeNode node) {
            try {
                gen.writeEndObject();                   //1 open {
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void visit(int idx, IndexedTreeNode node) {
            try {
                gen.writeFieldName(String.valueOf(idx));
                gen.writeStartObject();                 //2 close {
                gen.writeFieldName("data");
                dataSerializer.serialize(node.getData(), gen, serializers);
                if (node.getChildrenCount() > 0) {
                    gen.writeFieldName("chld");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void postVisit(IndexedTreeNode node) {
            try {
                gen.writeEndObject();                   //2 close }
                gen.writeEndObject();                   //1 close }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class TreeViewResponseSerializer extends StdSerializer<TreeViewResponse> {

        private StdSerializer indexedNodeSerializer;

        TreeViewResponseSerializer(StdSerializer indexedNodeSerializer) {
            super(TreeViewResponse.class);
            this.indexedNodeSerializer = indexedNodeSerializer;
        }

        @Override
        public void serialize(TreeViewResponse value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeFieldName("method_lookup");
            serializers.defaultSerializeValue(value.getMethodLookup(), gen);

            List<IndexedTreeNode<FrameNode>> nodes = value.getTree();

            for (IndexedTreeNode node : nodes) {
                gen.writeFieldName(String.valueOf(node.getIdx()));
                indexedNodeSerializer.serialize(node, gen, serializers);
            }
        }
    }
}
