package fk.prof.userapi.api;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowStorage;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.storage.AsyncStorage;
import org.junit.Assert;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Created by gaurav.ashok on 27/03/17.
 */
public class AggregationWindowSerDeTest {

    private static final String sampleStackTraces = "[\n" +
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

        String startime = "2017-03-01T07:00:00";
        ZonedDateTime startimeZ = ZonedDateTime.parse(startime + "Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);

        FinalizedAggregationWindow window = MockAggregationWindow.buildAggregationWindow(startime, () -> sampleStackTraces, 1800);

        AsyncStorage asyncStorage = new MockAggregationWindow.HashMapBasedStorage();
        AggregationWindowStorage storage = MockAggregationWindow.buildMockAggregationWindowStorage(asyncStorage);

        // store
        storage.store(window);

        // try fetch
        StorageBackedProfileLoader loader = new StorageBackedProfileLoader(asyncStorage);

        AggregatedProfileNamingStrategy file1 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, 1800, AggregatedProfileModel.WorkType.cpu_sample_work);
        Assert.assertTrue("aggregated profiles were not loaded", loader.load(file1) != null);

        AggregatedProfileNamingStrategy file2 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, 1800);
        Assert.assertTrue("aggregation summary were not loaded", loader.loadSummary(file2) != null);
    }
}
