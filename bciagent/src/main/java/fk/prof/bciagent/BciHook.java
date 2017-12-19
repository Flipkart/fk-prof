package fk.prof.bciagent;

import javassist.CtMethod;

@FunctionalInterface
interface BciHook<T> {
  void apply(T method) throws Exception;
}