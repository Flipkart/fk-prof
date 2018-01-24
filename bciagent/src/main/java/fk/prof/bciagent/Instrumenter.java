package fk.prof.bciagent;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;

public class Instrumenter {
  private static final String elapsedLocalVar = "$$$_evt_start_ts";
  private static final String fdLocalVar = "$$$_fd";

  private static final String fileTracer = GlobalCtx.class.getCanonicalName() + ".getIOTracer().forFile()";
  private static final String socketTracer = GlobalCtx.class.getCanonicalName() + ".getIOTracer().forSocket()";

  static class MethodEntry {

    static void elapsed(CtMethod m) throws Exception {
      String jStr = "";
      m.addLocalVariable(elapsedLocalVar, CtClass.longType);
      jStr += elapsedLocalVar + " = System.nanoTime();";
      m.insertBefore(jStr);
    }

    static void ss_read(CtMethod m) throws Exception {
      String jStr = "";
      m.addLocalVariable(elapsedLocalVar, CtClass.longType);
      jStr += elapsedLocalVar + " = System.nanoTime();";
      m.insertBefore(jStr);
    }
  }

  static class MethodExit {

    static void fs_open(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_fileStream_saveFDToLocalVar();
      jStr += "if(" + fdLocalVar + " != null) " +
              "{ " + fileTracer + ".open(" + fdLocalVar + ", this.path, " + expr_elapsedNanos() + "); }";
      m.insertAfter(jStr, true);
    }

    static void fs_read(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_fileStream_saveFDToLocalVar();
      jStr += fileTracer + ".read(" + fdLocalVar + ", (long)($_), " + expr_elapsedNanos() + ");";
      m.insertAfter(jStr, true);
    }

    static void fs_write(CtMethod m, int variant) throws Exception {
      String jStr = "";
      jStr += code_fileStream_saveFDToLocalVar();
      String count;
      if (variant == 1) {
          count = "1";  // write(byte)
      } else if (variant == 2) {
          count = "$1 == null ? 0 : $1.length"; // write(byte[])
      } else {
          count = "$1 == null ? 0 : $3"; // write(byte[], offset, length)
      }
      jStr += fileTracer + ".write(" + fdLocalVar + ", (long)(" + count + "), " + expr_elapsedNanos() + ");";
      m.insertAfter(jStr, true);
    }

    static void ss_read(CtMethod m, CtClass socketTimeoutExceptionClass) throws Exception {
      String elapsedLocalVar = "$$$_elapsed_ns";
      String jStr = "";
      jStr += code_sockStream_saveFDToLocalVar();
      jStr += "long " + elapsedLocalVar + " = " + expr_elapsedNanos() + ";";
      jStr += socketTracer + ".read(" + fdLocalVar + ", (long)($_), " + elapsedLocalVar + ", " + elapsedLocalVar + " > ((long)$4) * 1000000);";

      m.insertAfter(jStr, true);
    }

    static void ss_write(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_sockStream_saveFDToLocalVar();
      jStr += socketTracer + ".write(" + fdLocalVar + ", (long)($3)," + expr_elapsedNanos() + ");";
      m.insertAfter(jStr, true);
    }

    static void sock_connect(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_sockStream_saveFDToLocalVar();
      jStr += "if(" + fdLocalVar + " != null) " +
                "{ " + socketTracer + ".connect(" + fdLocalVar + ", $1.toString(), " + expr_elapsedNanos() + "); }";
      m.insertAfter(jStr, true);
    }

    static void sock_accept(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_sock_accept_saveFDToLocalVar();
      jStr += "if(" + fdLocalVar + " != null) " +
                "{ " + socketTracer + ".accept(" + fdLocalVar + ", $_.getInetAddress().toString(), " + expr_elapsedNanos() + "); }";
      m.insertAfter(jStr, true);
    }

    static void sockCh_connect(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_sockCh_saveFDToLocalVar();
      // TODO: non blocking??
      jStr += socketTracer + ".connect(" + fdLocalVar + ", $1.toString(), " + expr_elapsedNanos() + ");";
      m.insertAfter(jStr, true);
    }

    static void ioutil_read(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_ioUtil_saveFDToLocalVar();
      jStr += socketTracer + ".read(" + fdLocalVar + ", (long)($_), " + expr_elapsedNanos() + ", false);";
      m.insertAfter(jStr, true);
    }

    static void ioutil_write(CtMethod m) throws Exception {
      String jStr = "";
      jStr += code_ioUtil_saveFDToLocalVar();
      jStr += socketTracer + ".write(" + fdLocalVar + ", (long)($_)," + expr_elapsedNanos() + ");";
      m.insertAfter(jStr, true);
    }

  }

  static class ConstructorEntry {

    static void elapsed(CtConstructor c) throws Exception {
      String jStr = "";
      c.addLocalVariable(elapsedLocalVar, CtClass.longType);
      jStr += elapsedLocalVar + " = System.nanoTime();";
      c.insertBefore(jStr);
    }

  }

  static class ConstructorExit {

    static void sockCh(CtConstructor c) throws Exception {
      String jStr = "";
      jStr += code_sockCh_saveFDToLocalVar();
      jStr += socketTracer + ".accept(" + fdLocalVar + ", $3.toString(), " + expr_elapsedNanos() + ");";
      c.insertAfter(jStr, true);
    }

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
