package fk.prof.bciagent;


import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;


public class BciAgent {
  public static void premain(String agentArgs, Instrumentation inst) {
    System.out.println("Starting the agent");
    Class[] classes = inst.getAllLoadedClasses();
    List<Class> modifiableClasses = new ArrayList<>();
    System.out.println("Already loaded classes\n--------------");
    for(int i = 0; i< classes.length;i++) {
      boolean isModifiable = inst.isModifiableClass(classes[i]);
      System.out.println(isModifiable + " " + classes[i].getName());
      if(isModifiable) {
        modifiableClasses.add(classes[i]);
      }
    }
    System.out.println("----------------------");

    ClassFileTransformer transformer = new ProfileMethodTransformer();
    inst.addTransformer(transformer, true);

    Class[] retransformClasses = new Class[modifiableClasses.size()];
    for(int j = 0;j<modifiableClasses.size();j++) {
      retransformClasses[j] = modifiableClasses.get(j);
    }
    try {
      inst.retransformClasses(retransformClasses);
    } catch (Exception ex) {
      System.out.println("Error in retransforming: " + ex.getMessage());
      ex.printStackTrace();
    }
  }
}
