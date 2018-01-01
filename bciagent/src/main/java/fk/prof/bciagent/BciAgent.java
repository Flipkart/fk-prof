package fk.prof.bciagent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

public class BciAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        // TODO maybe redirect these printf via jni logger call
        System.out.println("Starting the agent");

        if(!FdAccessor.isInitialised()) {
            System.err.println("Unable to get the fd field from FileDescriptor class. Disabling instrumentation.");
            return;
        }

        ProfileMethodTransformer transformer = new ProfileMethodTransformer();

        if(!transformer.isInitialised()) {
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
}
