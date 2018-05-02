package fk.prof.nfr;

import fk.prof.ClosablePerfCtx;
import fk.prof.PerfCtx;
import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Created by gaurav.ashok on 11/04/17.
 */
public class Inception {

    int maxFanOut;

    int[] iterationCounts;

    Runnable[] work;

    RndGen rndGen;

    Consumer<Param>[] functions;

    PerfCtx[][] perfctxs;

    int perfCtxCount;

    int maxlevelDepth;

    AtomicInteger[] counters;

    public Inception(int[] iterations, Runnable[] work, PerfCtx[][] perfctxs, RndGen rndGen,
        int maxFanOut, int maxlevelDepth, AtomicInteger[] counters) {
        assert maxFanOut <= 20 : "fanout cannot be no more than 20";
        this.maxFanOut = maxFanOut;
        this.iterationCounts = iterations;
        this.work = work;

        this.perfctxs = perfctxs;
        this.perfCtxCount = perfctxs[0].length;
        this.maxlevelDepth = Math.max(11, maxlevelDepth);

        this.counters = counters;

        functions = (Consumer<Param>[]) Array.newInstance(Consumer.class, 20);

        functions[0] = this::level1;
        functions[1] = this::level2;
        functions[2] = this::level3;
        functions[3] = this::level4;
        functions[4] = this::level5;
        functions[5] = this::level6;
        functions[6] = this::level7;
        functions[7] = this::level8;
        functions[8] = this::level9;
        functions[9] = this::level10;
        functions[10] = this::level11;
        functions[11] = this::level12;
        functions[12] = this::level13;
        functions[13] = this::level14;
        functions[14] = this::level15;
        functions[15] = this::level16;
        functions[16] = this::level17;
        functions[17] = this::level18;
        functions[18] = this::level19;
        functions[19] = this::level20;

        this.rndGen = rndGen;
    }

    public void doWorkOnSomeLevel() {
//        System.out.println();
        jumpAround(new Param(maxlevelDepth, 0, 0));
    }

    private void level1(Param params) {
        jumpAround(params);
    }

    private void level2(Param params) {
        jumpAround(params);
    }

    private void level3(Param params) {
        jumpAround(params);
    }

    private void level4(Param params) {
        jumpAround(params);
    }

    private void level5(Param params) {
        jumpAround(params);
    }

    private void level6(Param params) {
        jumpAround(params);
    }

    private void level7(Param params) {
        jumpAround(params);
    }

    private void level8(Param params) {
        jumpAround(params);
    }

    private void level9(Param params) {
        jumpAround(params);
    }

    private void level10(Param params) {
        jumpAround(params);
    }

    private void level11(Param params) {
        jumpAround(params);
    }

    private void level12(Param params) {
        jumpAround(params);
    }

    private void level13(Param params) {
        jumpAround(params);
    }

    private void level14(Param params) {
        jumpAround(params);
    }

    private void level15(Param params) {
        jumpAround(params);
    }

    private void level16(Param params) {
        jumpAround(params);
    }

    private void level17(Param params) {
        jumpAround(params);
    }

    private void level18(Param params) {
        jumpAround(params);
    }

    private void level19(Param params) {
        jumpAround(params);
    }

    private void level20(Param params) {
        jumpAround(params);
    }

    private void jumpAround(Param params) {
        if (params.curDepth > params.maxDepth) {
            doSecondWorthOfWork();
            return;
        } else {
            Param next = params.next();
            next.jump();
        }
    }

    private void doSecondWorthOfWork() {
        for (int i = 0; i < iterationCounts.length; ++i) {
            try (ClosablePerfCtx ctx = perfctxs[i][rndGen.getInt(perfCtxCount)].open()) {
                for (int j = 0; j < iterationCounts[i]; ++j) {
                    work[i].run();
                    counters[i].incrementAndGet();
                }
            }
        }
    }

    private class Param {

        public int idx;

        public int maxDepth;

        public int curDepth;

        public Param(int maxDepth, int curDepth, int idx) {
            this.maxDepth = maxDepth;
            this.curDepth = curDepth;
            this.idx = idx;
        }

        public Param next() {

            return new Param(maxDepth, curDepth + 1, rndGen.getInt(1 + Math.min(curDepth, maxFanOut)));
        }

        public void jump() {
//            System.out.print(idx + " -> ");
            functions[idx].accept(this);
        }
    }
}
