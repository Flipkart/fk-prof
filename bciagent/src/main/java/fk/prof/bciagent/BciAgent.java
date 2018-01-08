package fk.prof.bciagent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Arrays;

public class BciAgent {

    /**
     * native method to signal that we are now initialized and ready to instrument. It enables the io tracing from recorder side.
     */
    private static native void bciAgentLoaded();

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Starting the agent");

        // getting all loaded classes after creating lambdas creates issues. Retransformation does not play nice with lambdas.
        Class[] classes = inst.getAllLoadedClasses();

        if (!verifyPrerequisites()) {
            return;
        }

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

    private static boolean verifyPrerequisites() {

        Class<?> fdaccessor;
        try {
            Class tracer = Class.forName("fk.prof.trace.IOTrace", true, ClassLoader.getSystemClassLoader());
            fdaccessor = Class.forName("fk.prof.FdAccessor", true, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            System.err.println(e.getMessage());
            return false;
        }

        try {
            Method m = fdaccessor.getDeclaredMethod("isInitialized");
            Boolean initialised = (Boolean) m.invoke(null);
            if (!initialised) {
                System.err.println("Unable to get the fd field from FileDescriptor class. Disabling instrumentation.");
            }
            return initialised;
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return false;
        }
    }
}
