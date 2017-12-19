package fk.prof.bciagent;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;

import java.util.HashMap;
import java.util.Map;

class ClassInstrumentHooks {
  final Map<String, EntryExitHooks<CtMethod>> methods = new HashMap<>();
  final Map<String, EntryExitHooks<CtConstructor>> constructors = new HashMap<>();

  boolean apply(CtClass ctClass) {

    return false;
  }
}
