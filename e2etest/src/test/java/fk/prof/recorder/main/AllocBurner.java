package fk.prof.recorder.main;

/**
 * @understands allocating too many objects, giving GC a hard time
 */
public class AllocBurner {
    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            GcBench.runBenchmark(120, 100, GcBench.kArraySize, GcBench.kMinTreeDepth, GcBench.kMaxTreeDepth);
        }
    }
}
