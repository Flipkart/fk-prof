package fk.prof.bciagent;

import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;

public class ProfileMethodTransformer implements ClassFileTransformer {
  private static final String elapsedLocalVar = "$$$_elpsed";
  private static final String fdLocalVar = "$$$_fd";

  private static final Map<String, ClassInstrumentHooks> INSTRUMENTED_CLASSES = new HashMap<>();
  private ClassPool pool;

  static {
    String klass;
    ClassInstrumentHooks hooks;

    EntryExitHooks<CtMethod> fs_default = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_fileStream_mexit);
    EntryExitHooks<CtMethod> fs_open = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_fileStream_mexit);

    klass = "java.io.FileInputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("open(Ljava/lang/String;)V", fs_open);
    hooks.methods.put("read()I", fs_default);
    hooks.methods.put("read([B)I", fs_default);
    hooks.methods.put("read([BII)I", fs_default);

    //creates recursion with print statements in bci code. uncomment when moving to jni and add hooks for open, write, close
//    klass = "java.io.FileOutputStream";

    EntryExitHooks<CtMethod> sock_input_default = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_sockStream_input_mexit);
    EntryExitHooks<CtMethod> sock_output_default = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_sockStream_output_mexit);
    EntryExitHooks<CtMethod> sock_connect = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_sock_connect_mexit);
    EntryExitHooks<CtMethod> sock_accept = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_sock_accept_mexit);

    klass = "java.net.SocketInputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("read([BIII)I", sock_input_default);
    hooks.methods.put("skip(J)J", sock_input_default);
    hooks.methods.put("available()I", sock_input_default);

    klass = "java.net.SocketOutputStream";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("socketWrite([BII)V", sock_output_default);

    klass = "java.net.Socket";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("connect(Ljava/net/SocketAddress;I)V", sock_connect);

    klass = "java.net.ServerSocket";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("accept()Ljava/net/Socket;", sock_accept);

    EntryExitHooks<CtMethod> sock_ch_connect = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_sockCh_connect_mexit);
    EntryExitHooks<CtConstructor> sock_ch_ctr = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_centry, ProfileMethodTransformer::instrument_sock_ch_cexit);

    klass = "sun.nio.ch.SocketChannelImpl";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("connect(Ljava/net/SocketAddress;)Z", sock_ch_connect);
    hooks.constructors.put("SocketChannelImpl(Ljava/nio/channels/spi/SelectorProvider;Ljava/io/FileDescriptor;Ljava/net/InetSocketAddress;)V", sock_ch_ctr);

    EntryExitHooks<CtMethod> ioutil_read = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_ioutil_read_mexit);
    EntryExitHooks<CtMethod> ioutil_write = new EntryExitHooks<>(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_ioutil_write_mexit);

    klass = "sun.nio.ch.IOUtil";
    hooks = new ClassInstrumentHooks();
    INSTRUMENTED_CLASSES.put(klass, hooks);
    hooks.methods.put("write(Ljava/io/FileDescriptor;Ljava/nio/ByteBuffer;JLsun/nio/ch/NativeDispatcher;)I", ioutil_write);
    hooks.methods.put("write(Ljava/io/FileDescriptor;[Ljava/nio/ByteBuffer;IILsun/nio/ch/NativeDispatcher;)J", ioutil_write);
    hooks.methods.put("read(Ljava/io/FileDescriptor;Ljava/nio/ByteBuffer;JLsun/nio/ch/NativeDispatcher;)I", ioutil_read);
    hooks.methods.put("read(Ljava/io/FileDescriptor;[Ljava/nio/ByteBuffer;IILsun/nio/ch/NativeDispatcher;)J", ioutil_read);

  }

  public ProfileMethodTransformer() {
    pool = ClassPool.getDefault();
  }

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
    try {
//      System.out.println("AGENT called for CLASS=" + className + ", classbeingredefined=" + (classBeingRedefined == null ? "null" : classBeingRedefined.getName())
//          + ", loader=" + (loader == null ? "null" : loader.toString()) + ", protectiondomain=" + (protectionDomain == null ? "null" : protectionDomain.toString()));
      if(className == null) {
        return null;
      }
      String normalizedClassName = className.replaceAll("/", ".");
      if(INSTRUMENTED_CLASSES.get(normalizedClassName) == null) {
        return null;
      }

      pool.insertClassPath(new ByteArrayClassPath(className, classfileBuffer));
      CtClass cclass = pool.get(normalizedClassName);
      boolean modified = false;

      if (!cclass.isFrozen()) {
//        System.out.println("EDITABLE CLASS=" + cclass.getName());
        ClassInstrumentHooks instrumentHooks = INSTRUMENTED_CLASSES.get(cclass.getName());

        if (instrumentHooks != null) {

          if (instrumentHooks.methods.size() > 0) {
            for (CtMethod currentMethod : cclass.getDeclaredMethods()) {
              System.out.println("Declared method=" + currentMethod.getLongName() + " identifier=" + currentMethod.getName() + currentMethod.getSignature());
              EntryExitHooks<CtMethod> hooks;
              if (((hooks = instrumentHooks.methods.get(currentMethod.getName() + currentMethod.getSignature())) != null) && !Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {
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

          if (instrumentHooks.constructors.size() > 0) {
            for (CtConstructor constructor : cclass.getDeclaredConstructors()) {
              System.out.println("Declared constructor=" + constructor.getLongName() + " identifier=" + constructor.getName() + constructor.getSignature());
              EntryExitHooks<CtConstructor> hooks;
              if ((hooks = instrumentHooks.constructors.get(constructor.getName() + constructor.getSignature())) != null) {
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

        }
      }

      if(modified) {
        return cclass.toBytecode();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  private static void instrument_elapsed_mentry(CtMethod m) throws Exception {
    String jStr = "";
    m.addLocalVariable(elapsedLocalVar, CtClass.longType);
    jStr += elapsedLocalVar + " = System.currentTimeMillis();";
    m.insertBefore(jStr);
  }

  private static void instrument_fileStream_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_fileStream_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": name=\" + this.path + \" FD=\" + $$$_fd + \" elapsed=\" + " + elapsedLocalVar + ");";
    //jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, $$$_fd);";
    m.insertAfter(jStr, true);
  }

  private static void instrument_sockStream_input_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_sockStream_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" return=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }

  private static void instrument_sockStream_output_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_sockStream_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" written=\" + $3 + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }

  private static void instrument_sock_connect_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_sockStream_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" connected=\" + connected + \" addr=\" + $1 + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }

  private static void instrument_sock_accept_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_sock_accept_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" connected=\" + ($_ == null ? null : $_.isConnected()) + \" addr=\" + ($_ == null ? null : $_.getInetAddress()) + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }

  private static void instrument_sockCh_connect_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_sockCh_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" remote_addr=\" + $1 + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }

  private static void instrument_ioutil_read_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_ioUtil_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" read=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }

  private static void instrument_ioutil_write_mexit(CtMethod m) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_ioUtil_saveFDToLocalVar();
    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" written=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
    m.insertAfter(jStr, true);
  }


  private static void instrument_elapsed_centry(CtConstructor c) throws Exception {
    String jStr = "";
    c.addLocalVariable(elapsedLocalVar, CtClass.longType);
    jStr += elapsedLocalVar + " = System.currentTimeMillis();";
    c.insertBefore(jStr);
  }

  private static void instrument_sock_ch_cexit(CtConstructor c) throws Exception {
    String jStr = "";
    jStr += codebuild_calculateElapsedMillis();
    jStr += codebuild_sockCh_saveFDToLocalVar();
    jStr += "System.out.println(\"CONSTRUCTOR=" + c.getLongName() + ": FD=\" + $$$_fd + \" remote_addr=\" + $3 + \" elapsed=\" + " + elapsedLocalVar + ");";
    c.insertAfter(jStr, true);
  }


  private static String codebuild_calculateElapsedMillis() {
    return elapsedLocalVar + " = System.currentTimeMillis() - " + elapsedLocalVar + ";";
  }

  private static String codebuild_fileStream_saveFDToLocalVar() {
    String jStr = "";
    jStr += "java.lang.reflect.Field fdField = this.fd == null ? null : this.fd.getClass().getDeclaredField(\"fd\");";
    jStr += "if (fdField != null) { fdField.setAccessible(true); }";
    jStr += "int " + fdLocalVar + " = fdField != null ? fdField.getInt(this.fd) : -1;";
    return jStr;
  }

  private static String codebuild_sock_accept_saveFDToLocalVar() {
    String jStr = "";
    jStr += "java.lang.reflect.Field fdField = ($_ == null || $_.getImpl() == null || $_.getImpl().getFileDescriptor() == null) ? null : $_.getImpl().getFileDescriptor().getClass().getDeclaredField(\"fd\");";
    jStr += "if (fdField != null) { fdField.setAccessible(true); }";
    jStr += "int " + fdLocalVar + " = fdField != null ? fdField.getInt($_.getImpl().getFileDescriptor()) : -1;";
    return jStr;
  }

  private static String codebuild_sockStream_saveFDToLocalVar() {
    String jStr = "";
    jStr += "java.lang.reflect.Field fdField = (this.impl == null || this.impl.getFileDescriptor() == null) ? null : this.impl.getFileDescriptor().getClass().getDeclaredField(\"fd\");";
    jStr += "if (fdField != null) { fdField.setAccessible(true); }";
    jStr += "int " + fdLocalVar + " = fdField != null ? fdField.getInt(this.impl.getFileDescriptor()) : -1;";
    return jStr;
  }

  private static String codebuild_ioUtil_saveFDToLocalVar() {
    String jStr = "";
    jStr += "java.lang.reflect.Field fdField = $1 == null ? null : $1.getClass().getDeclaredField(\"fd\");";
    jStr += "if (fdField != null) { fdField.setAccessible(true); }";
    jStr += "int " + fdLocalVar + " = fdField != null ? fdField.getInt($1) : -1;";
    return jStr;
  }

  private static String codebuild_sockCh_saveFDToLocalVar() {
    return "int " + fdLocalVar + " = fdVal;";
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


//Not necessary if channel impl classes are instrumented correctly
//    MethodInstrumentHooks net_connect = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_elapsed_mentry, ProfileMethodTransformer::instrument_net_connect_mexit);
//    klass = "sun.nio.ch.Net";
//    hooks = new ClassInstrumentHooks();
//    INSTRUMENTED_CLASSES.put(klass, hooks);
//    hooks.methods.put("connect(Ljava/net/ProtocolFamily;Ljava/io/FileDescriptor;Ljava/net/InetAddress;I)I", net_connect);

//  private static void instrument_net_connect_mexit(CtMethod m) throws Exception {
//    String jStr = "";
//    jStr += elapsedLocalVar + " = System.currentTimeMillis() - " + elapsedLocalVar + ";";
//    jStr += "java.lang.reflect.Field fdField = $2 == null ? null : $2.getClass().getDeclaredField(\"fd\");";
//    jStr += "if (fdField != null) { fdField.setAccessible(true); }";
//    jStr += "int $$$_fd = fdField != null ? fdField.getInt($2) : -1;";
//    jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" family=\" + ($1 == null ? \"null\" : $1.name()) + \" remote_addr=\" + $3 + \" remote_port=\" + $4 + \" elapsed=\" + " + elapsedLocalVar + ");";
//    //jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, $$$_fd);";
//    m.insertAfter(jStr, true);
//  }