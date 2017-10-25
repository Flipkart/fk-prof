package fk.prof.bciagent;

import javassist.ByteArrayClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProfileMethodTransformer implements ClassFileTransformer {
  private static final String PROFILE_ANNOTATION = "fk.prof.bciagent.Profile";
  private static final String PROFILE_ANNOTATION_TYPES = "value";
  private ClassPool pool;

  public ProfileMethodTransformer() {
    pool = ClassPool.getDefault();
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
    try {
      pool.insertClassPath(new ByteArrayClassPath(className, classfileBuffer));
      CtClass cclass = pool.get(className.replaceAll("/", "."));
      if (!cclass.isFrozen()) {
        for (CtMethod currentMethod : cclass.getDeclaredMethods()) {
          Annotation annotation = getAnnotation(currentMethod);
          if (annotation != null) {
            List<String> profileTypes = getProfileTypes(annotation);
            currentMethod.insertBefore(methodEntryInjection(currentMethod, profileTypes));
            currentMethod.insertAfter(methodExitInjection(currentMethod), true);
          }
        }
        return cclass.toBytecode();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  private Annotation getAnnotation(CtMethod method) {
    MethodInfo mInfo = method.getMethodInfo();
    // the attribute we are looking for is a runtime invisible attribute
    // use Retention(RetentionPolicy.RUNTIME) on the annotation to make it visible at runtime
    AnnotationsAttribute attInfo = (AnnotationsAttribute) mInfo
        .getAttribute(AnnotationsAttribute.invisibleTag);
    if (attInfo != null) {
      // this is the type name meaning use dots instead of slashes
      return attInfo.getAnnotation(PROFILE_ANNOTATION);
    }
    return null;
  }

  private List<String> getProfileTypes(Annotation annotation) {
    ArrayMemberValue value = (ArrayMemberValue) annotation
        .getMemberValue(PROFILE_ANNOTATION_TYPES);
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

  private String methodEntryInjection(CtMethod currentMethod, List<String> profileTypes) {
    String allTypes = String.join(",", profileTypes);
//    String jStr = "";
//    jStr += "System.out.println(\"Entered method ";
//    jStr += currentMethod.getLongName();
//    jStr += " with profile types ";
//    jStr += allTypes;
//    jStr += "\");";
//    return jStr;
    return "fk.prof.InstrumentationStub.entryTracepoint();";
  }

  private String methodExitInjection(CtMethod currentMethod) {
//    String jStr = "";
//    jStr += "System.out.println(\"Exited method ";
//    jStr += currentMethod.getLongName();
//    jStr += "\");";
//    return jStr;
    return "fk.prof.InstrumentationStub.exitTracepoint();";
  }
}
