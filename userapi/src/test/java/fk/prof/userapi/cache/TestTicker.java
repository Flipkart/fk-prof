package fk.prof.userapi.cache;

import com.google.common.base.Ticker;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by gaurav.ashok on 11/08/17.
 */
public class TestTicker extends Ticker {
    private final long beginTime;
    private final AtomicLong currTime;

    TestTicker() {
        beginTime = System.nanoTime();
        currTime = new AtomicLong(beginTime);
    }

    void advance(long duration, TimeUnit unit) {
        currTime.addAndGet(unit.toNanos(duration));
    }

    @Override
    public long read() {
        return currTime.get();
    }
}
