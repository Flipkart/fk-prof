package fk.prof.userapi.api;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.ObjectNotFoundException;
import fk.prof.storage.buffer.ByteBufferPoolFactory;
import io.vertx.core.Future;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by gaurav.ashok on 27/03/17.
 */
public class AggregationWindowSerDeTest {

    final String sampleStackTraces = "[\n" +
            "  [\"A()\",\"D()\",\"G()\"],\n" +
            "  [\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],[\"A()\",\"D()\"],\n" +
            "  [\"A()\", \"B()\", \"C()\"],[\"A()\", \"B()\", \"C()\"],\n" +
            "  [\"A()\",\"B()\"],[\"A()\",\"B()\"],[\"A()\",\"B()\"],[\"A()\",\"B()\"],[\"A()\",\"B()\"],\n" +
            "  [\"E()\", \"F()\", \"B()\", \"C()\"],[\"E()\", \"F()\", \"B()\", \"C()\"],[\"E()\", \"F()\", \"B()\", \"C()\"],\n" +
            "  [\"E()\", \"F()\", \"B()\"],[\"E()\", \"F()\", \"B()\"],\n" +
            "  [\"E()\", \"F()\", \"D()\"],[\"E()\", \"F()\", \"D()\"],[\"E()\", \"F()\", \"D()\"],[\"E()\", \"F()\", \"D()\"]\n" +
            "]";

    @Test
    public void testStoreAndLoadAggregaetdProfile_shouldBeAbleToStoreAndLoadAggregationWindowObject() throws Exception {
        AsyncStorage asyncStorage = new HashMapBasedStorage();
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(10);

        GenericObjectPool bufferPool = new GenericObjectPool<>(new ByteBufferPoolFactory(10_000_000, false), poolConfig);

        MetricRegistry mockMetricRegistry = mock(MetricRegistry.class);
        Timer mockTimer = mock(Timer.class);
        when(mockMetricRegistry.timer(any())).thenReturn(mockTimer);

        AggregationWindowStorage storage = new AggregationWindowStorage("profiles", asyncStorage, bufferPool, mockMetricRegistry);


        String startime = "2017-03-01T07:00:00";
        ZonedDateTime startimeZ = ZonedDateTime.parse(startime + "Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);
        FinalizedAggregationWindow window = MockAggregationWindow.buildAggregationWindow(startime, () -> sampleStackTraces, 1800);

        // store
        storage.store(window);

        // try fetch
        AggregatedProfileLoader loader = new AggregatedProfileLoader(asyncStorage);

        Future f1 =  Future.future();
        AggregatedProfileNamingStrategy file1 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, 1800, AggregatedProfileModel.WorkType.cpu_sample_work);
        loader.load(f1, file1);
        Assert.assertTrue("aggregated profiles were not loaded", f1.succeeded());

        Future f2 =  Future.future();
        AggregatedProfileNamingStrategy file2 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, 1800);
        loader.loadSummary(f2, file2);
        Assert.assertTrue("aggregation summary were not loaded", f2.succeeded());
    }

    class HashMapBasedStorage implements AsyncStorage {

        Map<String, byte[]> data = new HashMap<>();

        @Override
        public CompletableFuture<Void> storeAsync(String path, InputStream content, long length) {
            try {
                byte[] bytes = new byte[(int)length];
                int readBytes = content.read(bytes, 0, (int)length);
                assert readBytes == length : "couldn't read " + length + " bytes for storing";
                data.put(path, bytes);
                content.close();
            }
            catch(IOException e) {
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<InputStream> fetchAsync(String path) {
            if(data.containsKey(path)) {
                return CompletableFuture.completedFuture(new ByteArrayInputStream(data.get(path)));
            }
            else {
                return CompletableFuture.supplyAsync(() -> {
                    throw new ObjectNotFoundException(path);
                });
            }
        }

        @Override
        public CompletableFuture<Set<String>> listAsync(String prefixPath, boolean recursive) {
            throw new UnsupportedOperationException("list operation not supported");
        }
    }

}
