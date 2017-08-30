package fk.prof.nfr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fk.prof.PerfCtx;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Created by gaurav.ashok on 03/04/17.
 */
public class LoadGenApp {

    public  static final ObjectMapper om = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        if(args.length < 9) {
            System.err.println("too few params");
            return;
        }

        int loadTypes = 3;

        int totalThreadCounts = Integer.parseInt(args[0]);
        float[] loadShare = new float[loadTypes];
        for(int i = 0; i < loadTypes; ++i) {
            loadShare[i] = Float.parseFloat(args[i + 1]);
        }
        float factor = Float.parseFloat(args[4]);
        int traceDuplicatesFactor = Integer.parseInt(args[5]);
        int codeCvrgForPerfCtx = Integer.parseInt(args[6]);
        int maxFanOut = Integer.parseInt(args[7]);
        int stackLevelDepth = Integer.parseInt(args[8]);

        // generate trace names and corresponding perf ctx
        PerfCtx[][] perfctxs = new PerfCtx[loadTypes][traceDuplicatesFactor];
        String[] traceBaseNames = new String[]{"json-ser-de-ctx", "matrix-mult-ctx", "sort-and-find-ctx"};
        for(int i = 0; i < loadTypes; ++i) {
            for(int j = 0; j < traceDuplicatesFactor; ++j) {
                perfctxs[i][j] = new PerfCtx(traceBaseNames[i] + "_" + j, codeCvrgForPerfCtx);
            }
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

        totalTimings[0] /= 10;
        totalTimings[1] /= 10;
        totalTimings[2] /= 10;
        System.out.println("averaged out over 10 runs: " + totalTimings[0] + "\t" + totalTimings[1] + "\t" + totalTimings[2]);

        int[] iterationCounts = new int[loadTypes];
        for(int i = 0; i < loadTypes; ++i) {
            iterationCounts[i] = (int)(1000 * (factor * loadShare[i] / (traceDuplicatesFactor) / (totalTimings[i] / 1000.0f)));
        }

        System.out.println("iterations for each load: " + iterationCounts[0] + "\t" + iterationCounts[1] + "\t" + iterationCounts[2]);

        ExecutorService execSvc = Executors.newCachedThreadPool();

        for(int i = 0; i < totalThreadCounts; ++i) {
            final int tid = i;
            execSvc.submit(() -> {
                RndGen rndGen = new RndGen();
                Runnable[] work = getWork(rndGen);
                Inception inception = new Inception(iterationCounts, work, perfctxs, rndGen, maxFanOut, stackLevelDepth);
                while (true) {
                    long start = System.currentTimeMillis();
                    inception.doWorkOnSomeLevel();
                    long end = System.currentTimeMillis();
                    System.out.println("thread\t" + tid + "\t" + (end - start));

                    long totalTimeShare = (long)(factor * 1000);
                    if(end - start < totalTimeShare) {
                        try {
                            Thread.sleep(totalTimeShare - (end - start));
                        } catch(InterruptedException e) {
                            Thread.interrupted();
                        }
                    }

                    if (Thread.currentThread().isInterrupted()) {
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

    private static Runnable[] getWork(RndGen rndGen) {
        final JsonGenerator jsonGen = new JsonGenerator(rndGen);
        final MatrixMultiplicationLoad matMul = new MatrixMultiplicationLoad(80, rndGen);
        final SortingLoad sorting = new SortingLoad(512, rndGen);

        return new Runnable[] {
                () -> jsonGen.genJsonMap(8, 0.5f, 0.25f),
                () -> matMul.multiply(),
                () -> sorting.doWork()
        };
    }

    private static long[] findProportions() throws Exception {
        Runnable[] work = getWork(new RndGen());
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
