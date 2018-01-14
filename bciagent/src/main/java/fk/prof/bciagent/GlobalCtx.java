package fk.prof.bciagent;

import fk.prof.bciagent.tracer.IOTracer;

public class GlobalCtx {

    private static IOTracer ioTracer;

    public static void setIOTracer(IOTracer tracer) {
        if(GlobalCtx.ioTracer == null) {
            GlobalCtx.ioTracer = tracer;
        }
        else {
            throw new IllegalStateException("IOTracer being set multiple times.");
        }
    }

    public static IOTracer getIOTracer() {
        return GlobalCtx.ioTracer;
    }
}
