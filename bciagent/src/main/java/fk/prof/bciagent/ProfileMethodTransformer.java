package fk.prof.bciagent;

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;

public class ProfileMethodTransformer implements ClassFileTransformer {
  private static final String elapsedLocalVar = "$$$_elpsed";

  private static final Map<String, String[]> INSTRUMENTED_CLASSES = new HashMap<>();
  private ClassPool pool;

  static {
    INSTRUMENTED_CLASSES.put("java.io.FileInputStream", new String[] { "open", "close", "read" });
    //INSTRUMENTED_CLASSES.put("java.io.FileOutputStream", new String[] { "open", "close", "write" }); //creates recursion with print statements in bci code. uncomment when moving to jni
  }

  public ProfileMethodTransformer() {
    pool = ClassPool.getDefault();
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
    try {
      System.out.println("AGENT called for CLASS=" + className + ", classbeingredefined=" + (classBeingRedefined == null ? "null" : classBeingRedefined.getName())
          + ", loader=" + (loader == null ? "null" : loader.toString()) + ", protectiondomain=" + (protectionDomain == null ? "null" : protectionDomain.toString()));
      pool.insertClassPath(new ByteArrayClassPath(className, classfileBuffer));
      CtClass cclass = pool.get(className.replaceAll("/", "."));
      if (!cclass.isFrozen()) {
        System.out.println("EDITABLE CLASS: " + cclass.getName());
        String[] methodsToInstrument = INSTRUMENTED_CLASSES.get(cclass.getName());
        if(methodsToInstrument != null) {
          for (CtMethod currentMethod : cclass.getDeclaredMethods()) {
            if (exists(methodsToInstrument, currentMethod.getName()) && !Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {
              System.out.println("Transforming method " + currentMethod.getLongName() + " " + currentMethod.getName() + " " + currentMethod.getSignature());
              instrument_JAVA_IO_FILESTREAM_Method_Entry(currentMethod);
              instrument_JAVA_IO_FILESTREAM_Method_Exit(currentMethod);
            }
          }
        }
        return cclass.toBytecode();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  private String buildCallToMethod(CtMethod method) throws NotFoundException {
    String type = method.getReturnType().getName();
    if ("void".equals(type)) {
      return method.getName() + "($$);\n";
    } else {
      return "return " + method.getName() + "($$);\n";
    }
  }

  private Annotation getAnnotation(CtMethod method, String annotationName) {
    MethodInfo mInfo = method.getMethodInfo();
    // the attribute we are looking for is a runtime invisible attribute
    // use Retention(RetentionPolicy.RUNTIME) on the annotation to make it visible at runtime
    AnnotationsAttribute attInfo = (AnnotationsAttribute) mInfo
        .getAttribute(AnnotationsAttribute.invisibleTag);
    if (attInfo != null) {
      // this is the type name meaning use dots instead of slashes
      return attInfo.getAnnotation(annotationName);
    }
    return null;
  }

  private List<String> getAnnotationEnumArrValues(Annotation annotation, String annotationField) {
    ArrayMemberValue value = (ArrayMemberValue) annotation
        .getMemberValue(annotationField);
    if (value != null) {
      MemberValue[] values = value.getValue();
      List<String> parameterIndexes = new ArrayList<>();
      for (MemberValue val : values) {
        parameterIndexes.add(((EnumMemberValue) val).getValue());
      }
      return parameterIndexes;
    }
    return Collections.emptyList();
  }

  private String methodEntryInjection(CtMethod currentMethod) {
//    String jStr = "";
//    jStr += "System.out.println(\"Entered method ";
//    jStr += currentMethod.getLongName();
//    jStr += " with profile types ";
//    jStr += allTypes;
//    jStr += "\");";
//    return jStr;
//    return "fk.prof.InstrumentationStub.entryTracepoint();";
    return "System.out.println(\"Method called: " + currentMethod.getLongName() + "\");";
  }

  private String methodExitInjection(CtMethod currentMethod) {
//    String jStr = "";
//    jStr += "System.out.println(\"Exited method ";
//    jStr += currentMethod.getLongName();
//    jStr += "\");";
//    return jStr;
    return "fk.prof.InstrumentationStub.exitTracepoint();";
  }

  private void instrument_JAVA_IO_FILESTREAM_Method_Entry(CtMethod m) throws Exception {
    m.addLocalVariable(elapsedLocalVar, CtClass.longType);
    String jStr = "";
    jStr += elapsedLocalVar + " = System.currentTimeMillis();";

    m.insertBefore(jStr);
  }

  private void instrument_JAVA_IO_FILESTREAM_Method_Exit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += elapsedLocalVar + " = System.currentTimeMillis() - " + elapsedLocalVar + ";";
    jStr += "java.lang.reflect.Field fdField = this.fd.getClass().getDeclaredField(\"fd\");";
    jStr += "fdField.setAccessible(true);";
    jStr += "int $$$_fd = this.fd != null ? fdField.getInt(this.fd) : -1;";
    jStr += "System.out.println(\"" + m.getLongName() + ": name=\" + this.path + \" FD=\" + $$$_fd + \" elapsed=\" + " + elapsedLocalVar + ");";
    jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, $$$_fd);";
    m.insertAfter(jStr, true);
  }

  private boolean exists(String[] arr, String element) {
    for(String s: arr) {
      if(s.equals(element)) {
        return true;
      }
    }
    return false;
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


//Finding valid methods for bci (non native and non abstract)
//if (!Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {}