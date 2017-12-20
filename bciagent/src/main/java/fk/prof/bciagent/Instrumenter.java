package fk.prof.bciagent;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;

public class Instrumenter {
  private static final String elapsedLocalVar = "$$$_elpsed";
  private static final String fdLocalVar = "$$$_fd";

  static class MethodEntry {

    static void elapsed(CtMethod m) throws Exception {
      String jStr = "";
      m.addLocalVariable(elapsedLocalVar, CtClass.longType);
      jStr += elapsedLocalVar + " = System.currentTimeMillis();";
      m.insertBefore(jStr);
    }

  }

  static class MethodExit {

    static void fs_open(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_fileStream_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": name=\" + this.path + \" FD=\" + $$$_fd + \" elapsed=\" + " + elapsedLocalVar + ");";
      //jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, $$$_fd);";
      m.insertAfter(jStr, true);
    }

    static void fs_read(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_fileStream_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" return=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
      //jStr += "fk.prof.InstrumentationStub.fsOpEndTracepoint(" + elapsedLocalVar + ", this.path, $$$_fd);";
      m.insertAfter(jStr, true);
    }

    static void ss_read(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_sockStream_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" read=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

    static void ss_write(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_sockStream_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" written=\" + $3 + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

    static void sock_connect(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_sockStream_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" connected=\" + connected + \" addr=\" + $1 + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

    static void sock_accept(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_sock_accept_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" connected=\" + ($_ == null ? null : $_.isConnected()) + \" addr=\" + ($_ == null ? null : $_.getInetAddress()) + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

    static void sockCh_connect(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_sockCh_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" remote_addr=\" + $1 + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

    static void ioutil_read(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_ioUtil_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" read=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

    static void ioutil_write(CtMethod m) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_ioUtil_saveFDToLocalVar();
      jStr += "System.out.println(\"METHOD=" + m.getLongName() + ": FD=\" + $$$_fd + \" written=\" + $_ + \" elapsed=\" + " + elapsedLocalVar + ");";
      m.insertAfter(jStr, true);
    }

  }

  static class ConstructorEntry {

    static void elapsed(CtConstructor c) throws Exception {
      String jStr = "";
      c.addLocalVariable(elapsedLocalVar, CtClass.longType);
      jStr += elapsedLocalVar + " = System.currentTimeMillis();";
      c.insertBefore(jStr);
    }

  }

  static class ConstructorExit {

    static void sockCh(CtConstructor c) throws Exception {
      String jStr = "";
      jStr += codebuild_calculateElapsedMillis();
      jStr += codebuild_sockCh_saveFDToLocalVar();
      jStr += "System.out.println(\"CONSTRUCTOR=" + c.getLongName() + ": FD=\" + $$$_fd + \" remote_addr=\" + $3 + \" elapsed=\" + " + elapsedLocalVar + ");";
      c.insertAfter(jStr, true);
    }

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
