package fk.prof.recorder.utils;

import fk.prof.idl.Recording;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

import java.util.Map;
import java.util.Objects;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.Matchers.*;

public class Matchers {
    public static Matcher<Long> approximately(long base) {
        long err = base / 100;
        if (err == 0) err = 1;
        return approximately(base, err);
    }

    public static Matcher<Long> approximately(long base, long err) {
        return allOf(greaterThanOrEqualTo(base - err), lessThanOrEqualTo(base + err));
    }

    public static Matcher<Long> approximatelyBetween(long start, long end) {
        long err = start / 100;
        return allOf(greaterThanOrEqualTo(start - err), lessThanOrEqualTo(end + err));
    }

    public static Matcher<Long> between(Long start, Long end) {
        return allOf(greaterThanOrEqualTo(start), lessThanOrEqualTo(end));
    }

    public static Matcher<Integer> between(Integer start, Integer end) {
        return allOf(greaterThanOrEqualTo(start), lessThanOrEqualTo(end));
    }

    public static class IOTraceMatcher extends BaseMatcher<Recording.IOTrace> {
        Map<Long, Recording.MethodInfo> index;
        Recording.IOTrace iotrace;
        String[] ss;

        public IOTraceMatcher (Map<Long, Recording.MethodInfo> index, Recording.IOTrace iotrace, String[] ss) {
            this.index = index;
            this.iotrace = iotrace;
            this.ss = ss;
        }

        @Override
        public boolean matches(Object o) {
            if(o != null && o instanceof Recording.IOTrace) {
                final Recording.IOTrace t = (Recording.IOTrace) o;

                return t.getType() == iotrace.getType() &&
                        Objects.equals(iotrace.getRead(), t.getRead()) &&
                        Objects.equals(iotrace.getWrite(), t.getWrite()) &&
                        Objects.equals(iotrace.getFdId(), t.getFdId()) &&
                        matches(t.getStack());
            }
            return false;
        }

        private boolean matches(Recording.StackSample ss) {
            for(int i = 0; i < ss.getFrameCount(); ++i) {
                Recording.Frame f = ss.getFrame(i);
                if(!this.ss[i].equals(Util.frameStr(f, mi -> index.get(mi)))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void describeTo(Description description) {
            description
                    .appendText("\nExpected:\n")
                    .appendText(toStr(iotrace))
                    .appendText("\n")
                    .appendText(toStr(ss));
        }

        @Override
        public void describeMismatch(final Object item, final Description description) {
            Recording.IOTrace trace = (Recording.IOTrace) item;
            Recording.IOTrace traceDup = Recording.IOTrace.newBuilder(trace).clearStack().build();
            description
                    .appendText("\nActual:\n")
                    .appendText(toStr(traceDup))
                    .appendText("\n")
                    .appendText(toStr(trace.getStack()));
        }

        private String toStr(Recording.IOTrace iotrace) {
            return iotrace.toString();
        }

        private String toStr(String[] ss) {
            return String.join("\n", ss);
        }

        private String toStr(Recording.StackSample ss) {
            return String.join("\n",
                    ss.getFrameList().stream()
                            .map(f -> (CharSequence)Util.frameStr(f, mi -> index.get(mi)))::iterator);
        }
    }
}
