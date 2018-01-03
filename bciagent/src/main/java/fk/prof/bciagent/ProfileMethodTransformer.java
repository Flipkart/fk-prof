package fk.prof.bciagent;

import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.Function;

public class ProfileMethodTransformer implements ClassFileTransformer {
    private static final String elapsedLocalVar = "$$$_elapsed";
    private static final String fdLocalVar = "$$$_fd";
    private static final String timedoutLocalVar = "$$$_timedout";

    /**
     * native method to signal that we are able to instrument the java code. It enables the io tracing from recorder side.
     */
    private static native void bciStarted();

    /**
     * notifies the recorder that the bci failed for the provided class. Disables the io tracing if called at least once.
     *
     * @param className
     */
    private static native void bciFailed(String className);

    private volatile boolean bciFailed = false;
    private volatile boolean bciStarted = false;

    private final Map<String, ClassInstrumentHooks> INSTRUMENTED_CLASSES = new HashMap<>();
    private final ClassPool pool;

    private boolean initialised = false;

    private static class MethodInstrumentHooks {
        public final MethodBciHook entry;
        public final MethodBciHook exit;

        public MethodInstrumentHooks(MethodBciHook entry, MethodBciHook exit) {
            this.entry = entry;
            this.exit = exit;
        }
    }

    private static class ConstructorInstrumentHooks {
        public final ConstructorBciHook entry;
        public final ConstructorBciHook exit;

        public ConstructorInstrumentHooks(ConstructorBciHook entry, ConstructorBciHook exit) {
            this.entry = entry;
            this.exit = exit;
        }
    }

    private static class ClassInstrumentHooks {
        public final Map<String, MethodInstrumentHooks> methods = new HashMap<>();
        public final Map<String, ConstructorInstrumentHooks> constructors = new HashMap<>();
    }

    public ProfileMethodTransformer() {
        pool = ClassPool.getDefault();
        init();
    }

    public boolean isInitialised() {
        return initialised;
    }

    private void init() {
        String klass;
        ClassInstrumentHooks hooks;

        MethodInstrumentHooks fs_open = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_fileStream_open_mexit);
        MethodInstrumentHooks fs_read = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_fileStream_read_mexit);
        Function<Integer, MethodInstrumentHooks> fs_write =
                i -> new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, m -> ProfileMethodTransformer.instrument_fileStream_write_mexit(m, i));

        klass = "java.io.FileInputStream";
        hooks = new ClassInstrumentHooks();
        INSTRUMENTED_CLASSES.put(klass, hooks);
        hooks.methods.put("open(Ljava/lang/String;)V", fs_open);
        hooks.methods.put("read()I", fs_read);
        hooks.methods.put("read([B)I", fs_read);
        hooks.methods.put("read([BII)I", fs_read);

        klass = "java.io.FileOutputStream";
        hooks = new ClassInstrumentHooks();
        INSTRUMENTED_CLASSES.put(klass, hooks);
        hooks.methods.put("open(Ljava/lang/String;Z)V", fs_open);
        hooks.methods.put("write(I)V", fs_write.apply(1));
        hooks.methods.put("write([B)V", fs_write.apply(2));
        hooks.methods.put("write([BII)V", fs_write.apply(3));

        CtClass socketTimeoutExClass;
        try {
            socketTimeoutExClass = pool.get("java.net.SocketTimeoutException");
        } catch (NotFoundException e) {
            System.err.println(e.getMessage());
            return;
        }

        MethodInstrumentHooks sock_input_default = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_socketStream_input_mentry,
                m -> ProfileMethodTransformer.instrument_sockStream_input_mexit(m, socketTimeoutExClass));
        MethodInstrumentHooks sock_output_default = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_sockStream_output_mexit);
        MethodInstrumentHooks sock_connect = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_sock_connect_mexit);
        MethodInstrumentHooks sock_accept = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_sock_accept_mexit);

        klass = "java.net.SocketInputStream";
        hooks = new ClassInstrumentHooks();
        INSTRUMENTED_CLASSES.put(klass, hooks);
        hooks.methods.put("read([BIII)I", sock_input_default);
//    hooks.methods.put("skip(J)J", sock_input_default);
//    hooks.methods.put("available()I", sock_input_default);

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

        MethodInstrumentHooks sock_ch_connect = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_sockCh_connect_mexit);
        ConstructorInstrumentHooks sock_ch_ctr = new ConstructorInstrumentHooks(ProfileMethodTransformer::instrument_elapsed_centry, ProfileMethodTransformer::instrument_sock_ch_cexit);

        klass = "sun.nio.ch.SocketChannelImpl";
        hooks = new ClassInstrumentHooks();
        INSTRUMENTED_CLASSES.put(klass, hooks);
        hooks.methods.put("connect(Ljava/net/SocketAddress;)Z", sock_ch_connect);
        hooks.constructors.put("SocketChannelImpl(Ljava/nio/channels/spi/SelectorProvider;Ljava/io/FileDescriptor;Ljava/net/InetSocketAddress;)V", sock_ch_ctr);

        MethodInstrumentHooks ioutil_read = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_ioutil_read_mexit);
        MethodInstrumentHooks ioutil_write = new MethodInstrumentHooks(ProfileMethodTransformer::instrument_mentry, ProfileMethodTransformer::instrument_ioutil_write_mexit);

        klass = "sun.nio.ch.IOUtil";
        hooks = new ClassInstrumentHooks();
        INSTRUMENTED_CLASSES.put(klass, hooks);
        hooks.methods.put("write(Ljava/io/FileDescriptor;Ljava/nio/ByteBuffer;JLsun/nio/ch/NativeDispatcher;)I", ioutil_write);
        hooks.methods.put("write(Ljava/io/FileDescriptor;[Ljava/nio/ByteBuffer;IILsun/nio/ch/NativeDispatcher;)J", ioutil_write);
        hooks.methods.put("read(Ljava/io/FileDescriptor;Ljava/nio/ByteBuffer;JLsun/nio/ch/NativeDispatcher;)I", ioutil_read);
        hooks.methods.put("read(Ljava/io/FileDescriptor;[Ljava/nio/ByteBuffer;IILsun/nio/ch/NativeDispatcher;)J", ioutil_read);

        initialised = true;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        try {
            if (className == null) {
                return null;
            }
            String normalizedClassName = className.replaceAll("/", ".");
            if (INSTRUMENTED_CLASSES.get(normalizedClassName) == null) {
                return null;
            }

            pool.insertClassPath(new ByteArrayClassPath(className, classfileBuffer));
            CtClass cclass = pool.get(normalizedClassName);
            boolean modified = false;

            if (!cclass.isFrozen()) {
                ClassInstrumentHooks instrumentHooks = INSTRUMENTED_CLASSES.get(cclass.getName());

                if (instrumentHooks != null) {
                    if (instrumentHooks.methods.size() > 0) {
                        for (CtMethod currentMethod : cclass.getDeclaredMethods()) {
                            MethodInstrumentHooks hooks;
                            if (((hooks = instrumentHooks.methods.get(currentMethod.getName() + currentMethod.getSignature())) != null)
                                    && !Modifier.isNative(currentMethod.getModifiers()) && !currentMethod.isEmpty()) {
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
                            ConstructorInstrumentHooks hooks;
                            if ((hooks = instrumentHooks.constructors.get(constructor.getName() + constructor.getSignature())) != null) {
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

            if (modified) {
                if (!bciFailed && !bciStarted) {
                    bciStarted();
                    bciStarted = true;
                }
                return cclass.toBytecode();
            }
        } catch (Exception e) {
            bciFailed(className);
            bciFailed = true;
            System.err.println(e.getMessage());
        }
        return classfileBuffer;
    }

    private static void instrument_mentry(CtMethod m) throws Exception {
        String jStr = "";
        m.addLocalVariable(elapsedLocalVar, CtClass.longType);
        jStr += elapsedLocalVar + " = System.nanoTime();";
        m.insertBefore(jStr);
    }

    private static void instrument_fileStream_open_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_fileStream_saveFDToLocalVar();
        jStr += "if(" + fdLocalVar + " != null) " +
                "{ fk.prof.trace.IOTrace.File.open(" + fdLocalVar + ", this.path, " + expr_elapsedNanos() + "); }";
        m.insertAfter(jStr, true);
    }

    private static void instrument_fileStream_read_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_fileStream_saveFDToLocalVar();
        jStr += "fk.prof.trace.IOTrace.File.read(" + fdLocalVar + ", $_, " + expr_elapsedNanos() + ")";
        m.insertAfter(jStr, true);
    }

    private static void instrument_fileStream_write_mexit(CtMethod m, int variant) throws Exception {
        String jStr = "";
        jStr += code_fileStream_saveFDToLocalVar();
        String count;
        if (variant == 1) {
            count = "1";
        } else if (variant == 2) {
            count = "$1 == null ? 0 : $1.length";
        } else {
            count = "$3 - $2";
        }
        jStr += "fk.prof.trace.IOTrace.File.write(" + fdLocalVar + ", " + count + ", " + expr_elapsedNanos() + ")";
        m.insertAfter(jStr, true);
    }

    private static void instrument_socketStream_input_mentry(CtMethod m) throws Exception {
        String jStr = "";
        m.addLocalVariable(elapsedLocalVar, CtClass.longType);
        m.addLocalVariable(timedoutLocalVar, CtClass.booleanType);
        jStr += elapsedLocalVar + " = System.currentTimeMillis();";
        jStr += timedoutLocalVar + " = false;";
        m.insertBefore(jStr);
    }

    private static void instrument_sockStream_input_mexit(CtMethod m, CtClass socketTimeoutExceptionClass) throws Exception {
        m.addCatch("{ " + timedoutLocalVar + " = true; throw $e; }", socketTimeoutExceptionClass);
        String jStr = "";
        jStr += code_sockStream_saveFDToLocalVar();
        jStr += "fk.prof.trace.IOTrace.Socket.read(" + fdLocalVar + ", $_, " + expr_elapsedNanos() + ", " + timedoutLocalVar + ")";
        m.insertAfter(jStr, true);
    }

    private static void instrument_sockStream_output_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_sockStream_saveFDToLocalVar();
        jStr += "fk.prof.trace.IOTrace.Socket.write(" + fdLocalVar + ", $3 - $2," + expr_elapsedNanos() + ");";
        m.insertAfter(jStr, true);
    }

    private static void instrument_sock_connect_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_sockStream_saveFDToLocalVar();
        jStr += "if(" + fdLocalVar + " != null) " +
                "{ fk.prof.trace.IOTrace.Socket.connect(" + fdLocalVar + ", $1.toString(), " + expr_elapsedNanos() + "); }";
        m.insertAfter(jStr, true);
    }

    private static void instrument_sock_accept_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_sock_accept_saveFDToLocalVar();
        jStr += "if(" + fdLocalVar + " != null) " +
                "{ fk.prof.trace.IOTrace.Socket.accept(" + fdLocalVar + ", $_.getInetAddress().toString(), " + expr_elapsedNanos() + "); }";
        m.insertAfter(jStr, true);
    }

    private static void instrument_sockCh_connect_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_sockCh_saveFDToLocalVar();
        // non blocking??
        jStr += "if(" + fdLocalVar + " >= 0) " +
                "{ fk.prof.trace.IOTrace.Socket.connect(" + fdLocalVar + ", $1.toString(), " + expr_elapsedNanos() + "); }";
        m.insertAfter(jStr, true);
    }

    private static void instrument_ioutil_read_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_ioUtil_saveFDToLocalVar();
        jStr += "fk.prof.trace.IOTrace.Socket.read(" + fdLocalVar + ", $_, " + expr_elapsedNanos() + ", false)";
        m.insertAfter(jStr, true);
    }

    private static void instrument_ioutil_write_mexit(CtMethod m) throws Exception {
        String jStr = "";
        jStr += code_ioUtil_saveFDToLocalVar();
        jStr += "fk.prof.trace.IOTrace.Socket.write(" + fdLocalVar + ", $_," + expr_elapsedNanos() + ");";
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
        jStr += code_sockCh_saveFDToLocalVar();
        jStr += "if(" + fdLocalVar + " >= 0) " +
                "{ fk.prof.trace.IOTrace.Socket.accept(" + fdLocalVar + ", $3.toString(), " + expr_elapsedNanos() + "); }";
        c.insertAfter(jStr, true);
    }

    private static String expr_elapsedNanos() {
        return "System.nanoTime() - " + elapsedLocalVar;
    }

    private static String code_fileStream_saveFDToLocalVar() {
        String jStr = "";
        jStr += "java.io.FileDescriptor " + fdLocalVar + " = this.fd;";
        return jStr;
    }

    private static String code_sock_accept_saveFDToLocalVar() {
        String jStr = "";
        jStr += "java.io.FileDescriptor " +
                fdLocalVar +
                " = ($_ == null || $_.getImpl() == null || $_.getImpl().getFileDescriptor() == null)" +
                " ? null : $_.getImpl().getFileDescriptor();";
        return jStr;
    }

    private static String code_sockStream_saveFDToLocalVar() {
        String jStr = "";
        jStr += "java.io.FileDescriptor " +
                fdLocalVar +
                " = (this.impl == null || this.impl.getFileDescriptor() == null) ? null : this.impl.getFileDescriptor();";
        return jStr;
    }

    private static String code_ioUtil_saveFDToLocalVar() {
        String jStr = "";
        jStr += "java.io.FileDescriptor " + fdLocalVar + " = $1;";
        return jStr;
    }

    private static String code_sockCh_saveFDToLocalVar() {
        return "int " + fdLocalVar + " = fdVal;";
    }
}