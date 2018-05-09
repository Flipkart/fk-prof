package fk.prof.nfr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fk.prof.PerfCtx;
import fk.prof.nfr.config.CpuWorkConfig;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Created by gaurav.ashok on 03/04/17.
 */
public class CpuWorkLoad implements Runnable {

    static final ObjectMapper om = new ObjectMapper();

    private final CpuWorkConfig config;

    private final boolean isDebug;

    public CpuWorkLoad(CpuWorkConfig config, boolean isDebug) {
        Objects.requireNonNull(config);
        this.config = config;
        this.isDebug = isDebug;
    }

    @Override
    public void run() {
        int counterPrintPeriodInSec = 5;

        // generate trace names and corresponding perf ctx
        PerfCtx[][] perfctxs = new PerfCtx[config.loadTypes][config.traceDuplicationMultiplier];
        String[] traceBaseNames = new String[]{"json-ser-de-ctx", "matrix-mult-ctx",
            "sort-and-find-ctx"};
        for (int i = 0; i < config.loadTypes; ++i) {
            for (int j = 0; j < config.traceDuplicationMultiplier; ++j) {
                perfctxs[i][j] = new PerfCtx(traceBaseNames[i] + "_" + j, config.perfCtxCodeCoverage);
            }
        }

        om.registerModule(new Jdk8Module());
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        long[] totalTimings = new long[config.loadTypes];

        for (int i = 0; i < 15; ++i) {
            long[] timings = findProportions();
            if (i > 4) {
                totalTimings[0] += timings[0];
                totalTimings[1] += timings[1];
                totalTimings[2] += timings[2];
            }
            System.out.println(timings[0] + "\t" + timings[1] + "\t" + timings[2]);
        }

        totalTimings[0] /= 10;
        totalTimings[1] /= 10;
        totalTimings[2] /= 10;
        System.out.println(
            "averaged out over 10 runs: " + totalTimings[0] + "\t" + totalTimings[1] + "\t"
                + totalTimings[2]);

        int[] iterationCounts = new int[config.loadTypes];
        for (int i = 0; i < config.loadTypes; ++i) {
            iterationCounts[i] = (int) (1000 * (config.loadMultiplier * config.loadShare.get(i)
                / (config.traceDuplicationMultiplier) / (totalTimings[i] / 1000.0f)));
        }

        System.out.println(
            "iterations for each load: " + iterationCounts[0] + "\t" + iterationCounts[1] + "\t"
                + iterationCounts[2]);

        ExecutorService execSvc = Executors.newCachedThreadPool();

        AtomicInteger[] counters = {new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0)};

        for (int i = 0; i < config.totalThreadCnt; ++i) {
            final int tid = i;
            execSvc.submit(() -> {
                RndGen rndGen = new RndGen();
                Runnable[] work = getWork(rndGen);

                Inception inception = new Inception(iterationCounts, work, perfctxs, rndGen,
                                                    config.stacktraceFanOut, config.stacktraceDepth, counters);
                while (true) {
                    long start = System.currentTimeMillis();
                    inception.doWorkOnSomeLevel();
                    long end = System.currentTimeMillis();

                    long totalTimeShare = (long) (config.loadMultiplier * 1000);
                    if (end - start < totalTimeShare) {

                        long sleepFor = totalTimeShare - (end - start);
                        if (isDebug) {
                            System.out.print("thread " + tid + " sleeping for " + sleepFor);
                        }

                        try {
                            if (!Thread.currentThread().isInterrupted()) {
                                Thread.sleep(sleepFor);
                            }
                        } catch (InterruptedException e) {
                        }
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            });
        }

        execSvc.submit(() -> {
            int c1 = counters[0].get(), c2 = counters[1].get(), c3 = counters[2].get();
            while (true) {
                int _c1 = counters[0].get(), _c2 = counters[1].get(), _c3 = counters[2].get();

                LocalDateTime ldt = LocalDateTime.now();

                System.out.println(ldt + "\t" + (_c1 - c1) + "\t" + (_c2 - c2) + "\t" + (_c3 - c3));
                c1 = _c1;
                c2 = _c2;
                c3 = _c3;

                try {
                    Thread.sleep(counterPrintPeriodInSec * 1000);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Stopping cpu load");
                execSvc.shutdownNow();
                execSvc.awaitTermination(5000, TimeUnit.MILLISECONDS);
                System.out.println("Stopped cpu load");
            } catch (Exception e) {
                System.err.println("Issue in shutdown hook for cpu load: " + e.getMessage());
                e.printStackTrace();
            }
        }));
    }

    private Runnable[] getWork(RndGen rndGen) {
        final JsonGenerator jsonGen = new JsonGenerator(rndGen);
        final MatrixMultiplicationLoad matMul = new MatrixMultiplicationLoad(80, rndGen);
        final SortingLoad sorting = new SortingLoad(512, rndGen);

        return new Runnable[]{
            () -> {
                Map<String, Object> map = jsonGen.genJsonMap(6, 0.5f, 0.25f);
                try {
                    String serialized = om.writeValueAsString(map);
                    Map<String, Object> map_back = om.readValue(serialized, Map.class);
                } catch (Exception e) {
                    // ignore
                }
            },
            () -> matMul.multiply(),
            () -> sorting.doWork()
        };
    }

    private long[] findProportions() {
        Runnable[] work = getWork(new RndGen());
        return Stream.of(work).map(w -> findTimings(w, 1000)).mapToLong(l -> l).toArray();
    }

    private long findTimings(Runnable work, int iterations) {
        long start = System.currentTimeMillis();

        for (int i = 0; i < iterations; ++i) {
            work.run();
        }

        long end = System.currentTimeMillis();

        return end - start;
    }
}
