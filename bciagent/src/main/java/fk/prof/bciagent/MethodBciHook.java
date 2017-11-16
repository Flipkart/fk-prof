package fk.prof.bciagent;

import javassist.CtMethod;

@FunctionalInterface
public interface MethodBciHook {
  void apply(CtMethod method) throws Exception;
}