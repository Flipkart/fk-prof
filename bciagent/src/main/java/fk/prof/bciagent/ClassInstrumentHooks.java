package fk.prof.bciagent;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.Modifier;

import java.util.HashMap;
import java.util.Map;

class ClassInstrumentHooks {
  final Map<String, EntryExitHooks<CtMethod>> methods = new HashMap<>();
  final Map<String, EntryExitHooks<CtConstructor>> constructors = new HashMap<>();

  /**
   * Performs bci on relevant methods and constructors and returns true if any of them are modified
   * @param cclass
   * @return
   * @throws Exception
   */
  boolean apply(CtClass cclass) throws Exception {
    boolean methodsModified = applyToMethods(cclass);
    boolean constructorsModified = applyToConstructors(cclass);
    return methodsModified || constructorsModified;
  }

  private boolean applyToMethods(CtClass cclass) throws Exception {
    boolean modified = false;
    if (methods.size() > 0) {
      for (CtMethod currentMethod : cclass.getDeclaredMethods()) {
        System.out.println("Declared method=" + currentMethod.getLongName() + " identifier=" + currentMethod.getName() + currentMethod.getSignature());
        EntryExitHooks<CtMethod> hooks;
        if (((hooks = methods.get(currentMethod.getName() + currentMethod.getSignature())) != null) && !Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {
          System.out.println("Transformed method=" + currentMethod.getLongName() + " identifier=" + currentMethod.getName() + currentMethod.getSignature());
          if (hooks.entry != null) {
            hooks.entry.apply(currentMethod);
            modified = true;
          }
          if (hooks.exit != null) {
            hooks.exit.apply(currentMethod);
            modified = true;
          }
        }
      }
    }
    return modified;
  }

  private boolean applyToConstructors(CtClass cclass) throws Exception {
    boolean modified = false;
    if (constructors.size() > 0) {
      for (CtConstructor constructor : cclass.getDeclaredConstructors()) {
        System.out.println("Declared constructor=" + constructor.getLongName() + " identifier=" + constructor.getName() + constructor.getSignature());
        EntryExitHooks<CtConstructor> hooks;
        if ((hooks = constructors.get(constructor.getName() + constructor.getSignature())) != null) {
          System.out.println("Transformed constructor=" + constructor.getLongName() + " identifier=" + constructor.getName() + constructor.getSignature());
          if (hooks.entry != null) {
            hooks.entry.apply(constructor);
            modified = true;
          }
          if (hooks.exit != null) {
            hooks.exit.apply(constructor);
            modified = true;
          }
        }
      }
    }
    return modified;
  }
}
