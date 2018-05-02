package fk.prof.bciagent;

import fk.prof.bciagent.tracer.IOTracer;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;

public class BciAgent {

    /**
     * native method to signal that we are now initialized and ready to instrument. It enables io tracing from the recorder side.
     */
    private static native void bciAgentLoaded();

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Starting the agent");

        // Saw some issues when this was below some lambda creating calls.
        // Retransformation does not play nice with lambdas.
        Class[] classes = inst.getAllLoadedClasses();

        if (!verifyPrerequisites()) {
            return;
        }

        GlobalCtx.setIOTracer(new IOTracer());

        ProfileMethodTransformer transformer = new ProfileMethodTransformer();

        if (!transformer.init()) {
            System.err.println("Unable to initialize class transformer. Disabling instrumentation.");
            return;
        }

        inst.addTransformer(transformer, true);

        Class[] retransformClasses = Arrays.stream(classes)
                .filter(inst::isModifiableClass)
                .toArray(Class[]::new);

        try {
            bciAgentLoaded();
        } catch (LinkageError e) {
            System.err.println("native methods not linked. libfkpagent.so might not be loaded. Disabling instrumentation.");
            return;
        }

        try {
            inst.retransformClasses(retransformClasses);
        } catch (Exception ex) {
            System.err.println("Error in retransforming: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public static boolean verifyPrerequisites() {
        Boolean initialised = FdAccessor.isInitialized();
        if (!initialised) {
            System.err.println("Unable to get the fd field from FileDescriptor class. Disabling instrumentation.");
        }
        return initialised;
    }
}
