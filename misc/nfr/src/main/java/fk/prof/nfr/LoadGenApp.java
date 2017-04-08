package fk.prof.nfr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fk.prof.ClosablePerfCtx;
import fk.prof.PerfCtx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by gaurav.ashok on 03/04/17.
 */
public class LoadGenApp {

    public  static final ObjectMapper om = new ObjectMapper();

    static PerfCtx serdeCtx = new PerfCtx("json-ser-de-ctx", 20);
    static PerfCtx multiplyCtx = new PerfCtx("matrix-mult-ctx", 20);
    static PerfCtx sortingCtx = new PerfCtx("sort-and-find-ctx", 20);

    static PerfCtx[] perfCtxs = new PerfCtx[] {serdeCtx, multiplyCtx, sortingCtx};

    public static void main(String[] args) throws Exception {

        if(args.length < 4) {
            System.err.println("too few params");
            return;
        }

        int loadTypes = 3;

        int totalThreadCounts = Integer.parseInt(args[0]);
        float[] loadShare = new float[loadTypes];
        for(int i = 0; i < loadTypes; ++i) {
            loadShare[i] = Float.parseFloat(args[i + 1]);
        }

        om.registerModule(new Jdk8Module());
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        long[] totalTimings = new long[loadTypes];

        for(int i = 0; i < 12; ++i) {
            long[] timings = findProportions();
            if(i > 1) {
                totalTimings[0] += timings[0];
                totalTimings[1] += timings[1];
                totalTimings[2] += timings[2];
            }
            System.out.println(timings[0] + "\t" + timings[1] + "\t" + timings[2]);
        }

        System.out.println("averaged out over 10 runs: " + totalTimings[0]/10 + "\t" + totalTimings[1]/10 + "\t" + totalTimings[2]/10);

        int[] iterationCounts = new int[loadTypes];
        for(int i = 0; i < loadTypes; ++i) {
            iterationCounts[i] = (int)(1000 * (loadShare[i] / (totalTimings[i] / 1000.0f / 10.0f)));
        }

        System.out.println("iterations for each load: " + iterationCounts[0] + "\t" + iterationCounts[1] + "\t" + iterationCounts[2]);

        ExecutorService execSvc = Executors.newCachedThreadPool();

        for(int i = 0; i < totalThreadCounts; ++i) {
            execSvc.submit(() -> {
                Runnable[] work = getWork();
                while (true) {
                    doSecondWorthWork(iterationCounts, work);
                    if(Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Stopping");
                execSvc.shutdownNow();
                execSvc.awaitTermination(5000, TimeUnit.MILLISECONDS);
                System.out.println("Stopped");
            }
            catch (Exception e) {
                System.err.println("Issue in shutdown hook: " + e.getMessage());
                e.printStackTrace();
            }
        }));
    }

    private static void doSecondWorthWork(int[] iterationCounts, Runnable[] work) {
        for(int i = 0; i < iterationCounts.length; ++i) {
            try (ClosablePerfCtx ctx = perfCtxs[i].open()) {
                for (int j = 0; j < iterationCounts[i]; ++j) {
                    work[i].run();
                }
            }
        }
    }

    private static Runnable[] getWork() {
        final JsonGenerator jsonGen = new JsonGenerator(1000);
        final MatrixMultiplicationLoad matMul = new MatrixMultiplicationLoad(128);
        final SortingLoad sorting = new SortingLoad(512);

        return new Runnable[] {
                () -> jsonGen.genJsonMap(8, 0.35f, 0.15f),
                () -> matMul.multiply(),
                () -> sorting.doWork()
        };
    }

    private static long[] findProportions() throws Exception {
        Runnable[] work = getWork();
        return Stream.of(work).map(w -> findTimings(w, 1000)).mapToLong(l -> l).toArray();
    }

    private static long findTimings(Runnable work, int iterations) {
        long start = System.currentTimeMillis();

        for(int i = 0; i < iterations; ++i) {
            work.run();
        }

        long end = System.currentTimeMillis();

        return end - start;
    }
}
