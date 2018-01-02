package fk.prof.bciagent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class BciAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        // TODO maybe redirect these printf via jni logger call
        System.out.println("Starting the agent");

        if (!verifyPrerequisites()) {
            return;
        }

        ProfileMethodTransformer transformer = new ProfileMethodTransformer();

        if (!transformer.isInitialised()) {
            System.err.println("Unable to initialize class transformer. Disabling instrumentation.");
            return;
        }

        Class[] classes = inst.getAllLoadedClasses();
        List<Class> modifiableClasses = new ArrayList<>();
        for (int i = 0; i < classes.length; i++) {
            boolean isModifiable = inst.isModifiableClass(classes[i]);
            if (isModifiable) {
                modifiableClasses.add(classes[i]);
            }
        }


        inst.addTransformer(transformer, true);

        Class[] retransformClasses = new Class[modifiableClasses.size()];
        for (int j = 0; j < modifiableClasses.size(); j++) {
            retransformClasses[j] = modifiableClasses.get(j);
        }
        try {
            inst.retransformClasses(retransformClasses);
        } catch (Exception ex) {
            System.err.println("Error in retransforming: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static boolean verifyPrerequisites() {

        Class fdaccessor;
        try {
            Class tracer = Class.forName("fk.prof.trace.IOTrace", true, ClassLoader.getSystemClassLoader());
            fdaccessor = Class.forName("fk.prof.FdAccessor", true, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException e) {
            System.err.println(e.getMessage());
            return false;
        } catch (LinkageError le) {
            System.err.println(le.getMessage());
            return false;
        }

        try {
            Method m = fdaccessor.getDeclaredMethod("isInitialised");
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
