package fk.prof.bciagent;

import fk.prof.InstrumentationStub;

import java.lang.instrument.Instrumentation;


public class BciAgent {
  public static void premain(String agentArgs, Instrumentation inst) {
    System.out.println("Starting the agent");
    inst.addTransformer(new ProfileMethodTransformer());
  }
}
