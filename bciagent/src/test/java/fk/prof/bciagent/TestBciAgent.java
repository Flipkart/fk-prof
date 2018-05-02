package fk.prof.bciagent;

import fk.prof.bciagent.tracer.IOTracer;

import java.lang.instrument.Instrumentation;
import java.util.*;

public class TestBciAgent {

    public static List<Event> tracerEvents = Collections.synchronizedList(new ArrayList<>());

    public static void premain(String agentArgs, Instrumentation inst) throws Exception {
        // getting all loaded classes after creating lambdas creates issues. Retransformation does not play nice with lambdas.
        Class[] classes = inst.getAllLoadedClasses();

        // trigger loading for test event classes
        new Event(Util.getCallerName(), 0, 0, 0, Event.Socket.accept(""));
        new Event(Util.getCallerName(), 0, 0, 0, Event.File.open(""));

        // mocked tracer
        GlobalCtx.setIOTracer(new IOTracer(new MockSocketTracer(tracerEvents), new MockFileTracer(tracerEvents)));

        ProfileMethodTransformer transformer = new ProfileMethodTransformer();

        if (!transformer.init()) {
            throw new RuntimeException("Unable to initialize class transformer. Disabling instrumentation.");
        }

        inst.addTransformer(transformer, true);

        Class[] retransformClasses = Arrays.stream(classes)
                .filter(inst::isModifiableClass)
                .toArray(Class[]::new);

        try {
            inst.retransformClasses(retransformClasses);
        } catch (Exception ex) {
            throw new RuntimeException("Error in retransforming: " + ex.getMessage());
        }
    }
}
