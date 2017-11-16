package fk.prof.bciagent;

import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;

public class ProfileMethodTransformer implements ClassFileTransformer {
  private static final String elapsedLocalVar = "$$$_elpsed";
  private static final String fdLocalVar = "$$$_fd";

  private static final Map<String, Map<String, MethodInstrumentHooks>> INSTRUMENTED_CLASSES = new HashMap<>();
  private ClassPool pool;

  private static class MethodInstrumentHooks {
    MethodBciHook entry;
    MethodBciHook exit;

    public MethodInstrumentHooks(MethodBciHook entry, MethodBciHook exit) {
      this.entry = entry;
      this.exit = exit;
    }
  }

  static {
    MethodInstrumentHooks fs_entry_plain_exit_filename = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_JAVA_IO_FILESTREAM_Method_Entry, ProfileMethodTransformer::instrument_JAVA_IO_FILESTREAM_Method_Exit);
    MethodInstrumentHooks fs_entry_plain_exit_plain = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_JAVA_IO_FILESTREAM_Method_Entry, ProfileMethodTransformer::instrument_JAVA_IO_FILESTREAM_Method_Exit);
    MethodInstrumentHooks fs_entry_fd_exit_plain = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_JAVA_IO_FILESTREAM_Close_Method_Entry, ProfileMethodTransformer::instrument_JAVA_IO_FILESTREAM_Close_Method_Exit);

    INSTRUMENTED_CLASSES.put("java.io.FileInputStream", new HashMap<>());
    INSTRUMENTED_CLASSES.get("java.io.FileInputStream").put("open", fs_entry_plain_exit_filename);
    INSTRUMENTED_CLASSES.get("java.io.FileInputStream").put("read", fs_entry_plain_exit_plain);
    INSTRUMENTED_CLASSES.get("java.io.FileInputStream").put("close", fs_entry_fd_exit_plain);

    //creates recursion with print statements in bci code. uncomment when moving to jni
//    INSTRUMENTED_CLASSES.put("java.io.FileOutputStream", new HashMap<>());
//    INSTRUMENTED_CLASSES.get("java.io.FileOutputStream").put("open", fs_entry_plain_exit_filename);
//    INSTRUMENTED_CLASSES.get("java.io.FileOutputStream").put("write", fs_entry_plain_exit_plain);
//    INSTRUMENTED_CLASSES.get("java.io.FileOutputStream").put("close", fs_entry_fd_exit_plain);

    INSTRUMENTED_CLASSES.put("java.net.Socket", null);
  }

  public ProfileMethodTransformer() {
    pool = ClassPool.getDefault();
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
    try {
//      System.out.println("AGENT called for CLASS=" + className + ", classbeingredefined=" + (classBeingRedefined == null ? "null" : classBeingRedefined.getName())
//          + ", loader=" + (loader == null ? "null" : loader.toString()) + ", protectiondomain=" + (protectionDomain == null ? "null" : protectionDomain.toString()));
      pool.insertClassPath(new ByteArrayClassPath(className, classfileBuffer));
      CtClass cclass = pool.get(className.replaceAll("/", "."));

      if (!cclass.isFrozen()) {
        System.out.println("EDITABLE CLASS=" + cclass.getName());
        Map<String, MethodInstrumentHooks> methodsToInstrument = INSTRUMENTED_CLASSES.get(cclass.getName());
        if(methodsToInstrument != null && methodsToInstrument.size() > 0) {
          for (CtMethod currentMethod : cclass.getDeclaredMethods()) {
            System.out.println("Declared method " + currentMethod.getLongName());
            MethodInstrumentHooks hooks;
            if (((hooks = methodsToInstrument.get(currentMethod.getName())) != null) && !Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {
              System.out.println("Transforming method " + currentMethod.getLongName() + " " + currentMethod.getName() + " " + currentMethod.getSignature());

              if(hooks.entry != null) {
                hooks.entry.apply(currentMethod);
              }
              if(hooks.exit != null) {
                hooks.exit.apply(currentMethod);
              }
            }
          }
          return cclass.toBytecode();
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }


  private static void instrument_JAVA_IO_FILESTREAM_Method_Entry(CtMethod m) throws Exception {
    String jStr = "";
    m.addLocalVariable(elapsedLocalVar, CtClass.longType);
    jStr += elapsedLocalVar + " = System.currentTimeMillis();";
    m.insertBefore(jStr);
  }

  private static void instrument_JAVA_IO_FILESTREAM_Method_Exit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += elapsedLocalVar + " = System.currentTimeMillis() - " + elapsedLocalVar + ";";
    jStr += "java.lang.reflect.Field fdField = this.fd.getClass().getDeclaredField(\"fd\");";
    jStr += "fdField.setAccessible(true);";
    jStr += "int $$$_fd = this.fd != null ? fdField.getInt(this.fd) : -1;";
    jStr += "System.out.println(\"METHOD EXIT: " + m.getLongName() + ": name=\" + this.path + \" FD=\" + $$$_fd + \" elapsed=\" + " + elapsedLocalVar + ");";
    //jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, $$$_fd);";
    m.insertAfter(jStr, true);
  }

  private static void instrument_JAVA_IO_FILESTREAM_Close_Method_Entry(CtMethod m) throws Exception {
    String jStr = "";
    m.addLocalVariable(elapsedLocalVar, CtClass.longType);
    m.addLocalVariable(fdLocalVar, CtClass.intType);
    jStr += elapsedLocalVar + " = System.currentTimeMillis();";
    jStr += "java.lang.reflect.Field fdField = this.fd.getClass().getDeclaredField(\"fd\");";
    jStr += "fdField.setAccessible(true);";
    jStr += fdLocalVar + " = this.fd != null ? fdField.getInt(this.fd) : -1;";
    m.insertBefore(jStr);
  }

  private static void instrument_JAVA_IO_FILESTREAM_Close_Method_Exit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += elapsedLocalVar + " = System.currentTimeMillis() - " + elapsedLocalVar + ";";
    jStr += "System.out.println(\"METHOD EXIT: " + m.getLongName() + ": name=\" + this.path + \" FD=\" + " + fdLocalVar + " + \" elapsed=\" + " + elapsedLocalVar + ");";
//    jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, " + fdLocalVar + ");";
    m.insertAfter(jStr, true);
  }
}




//INSTRUMENT NATIVE METHODS
//private static final String PROFILE_OFF_CPU_ANNOTATION = "fk.prof.bciagent.Profile";
//private static final String NATIVE_METHOD_WRAPPER = "$$$_FKP_NATIVE_WRAPPER_$$$_";
//Do the following as well as call setNativePrefix in java.lang.instrument
//          if(Modifier.isNative(currentMethod.getModifiers())) {
//            System.out.println("Transforming method " + currentMethod.getLongName());
//            String currentMethodName = currentMethod.getName();
//            CtMethod wrapperMethod = CtNewMethod.copy(currentMethod, currentMethodName, cclass, null);
//            wrapperMethod.setModifiers(Modifier.clear(wrapperMethod.getModifiers(), Modifier.NATIVE));
//            wrapperMethod.setBody(buildCallToMethod(currentMethod));
//
//            currentMethod.setName(NATIVE_METHOD_WRAPPER + currentMethod.getName());
//            currentMethod.setModifiers(Modifier.setPrivate(currentMethod.getModifiers()) | Modifier.FINAL);

//            wrapperMethod.insertBefore(methodEntryInjection(currentMethod));
//            wrapperMethod.insertAfter(methodExitInjection(currentMethod), true);
//            cclass.addMethod(wrapperMethod);
//          }

//
//  private String buildCallToMethod(CtMethod method) throws NotFoundException {
//    String type = method.getReturnType().getName();
//    if ("void".equals(type)) {
//      return method.getName() + "($$);\n";
//    } else {
//      return "return " + method.getName() + "($$);\n";
//    }
//  }

//Finding valid methods for bci (non native and non abstract)
//if (!Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {}


//  private Annotation getAnnotation(CtMethod method, String annotationName) {
//    MethodInfo mInfo = method.getMethodInfo();
//    // the attribute we are looking for is a runtime invisible attribute
//    // use Retention(RetentionPolicy.RUNTIME) on the annotation to make it visible at runtime
//    AnnotationsAttribute attInfo = (AnnotationsAttribute) mInfo
//        .getAttribute(AnnotationsAttribute.invisibleTag);
//    if (attInfo != null) {
//      // this is the type name meaning use dots instead of slashes
//      return attInfo.getAnnotation(annotationName);
//    }
//    return null;
//  }
//
//  private List<String> getAnnotationEnumArrValues(Annotation annotation, String annotationField) {
//    ArrayMemberValue value = (ArrayMemberValue) annotation
//        .getMemberValue(annotationField);
//    if (value != null) {
//      MemberValue[] values = value.getValue();
//      List<String> parameterIndexes = new ArrayList<>();
//      for (MemberValue val : values) {
//        parameterIndexes.add(((EnumMemberValue) val).getValue());
//      }
//      return parameterIndexes;
//    }
//    return Collections.emptyList();
//  }