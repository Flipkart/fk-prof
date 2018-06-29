package fk.prof.bciagent;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public class JettyInstrumentors {

    private static String localThreadVar = "$$$_currentThread";
    private static String hcsHttpTask = "$$$_httpTask";

    private final ClassPool pool;
    private final CtClass runnableClass;
    private final CtClass threadClass;
    private final CtClass asyncTaskCtxClass;
    private final CtClass httpTaskClass;

    public JettyInstrumentors(ClassPool pool) throws NotFoundException {
        this.pool = pool;
        this.runnableClass = pool.get("java.lang.Runnable");
        this.threadClass = pool.get("java.lang.Thread");
        this.asyncTaskCtxClass = pool.get("fk.prof.AsyncTaskCtx");
        this.httpTaskClass = pool.get("fk.prof.HttpTask");
    }

    public boolean transformThreadPoolExecutor(CtClass cclass) throws NotFoundException, CannotCompileException {
        CtMethod mExecute = Util.getMethod(cclass,
            "execute",
            "(Ljava/lang/Runnable;)V");
        mExecute.insertBefore("$1 = fk.prof.TracingRunnable.wrap($1);");

        CtMethod mbefore = Util.getMethod(cclass,
            "beforeExecute",
            "(Ljava/lang/Thread;Ljava/lang/Runnable;)V");
        mbefore.insertBefore("{" +
                "if($2 instanceof fk.prof.TracingRunnable) {" +
                    "((fk.prof.TracingRunnable)$2).beforeExecute($1);" +
                "}" +
            "}");

        CtMethod mAfter = Util.getMethod(cclass,
            "afterExecute",
            "(Ljava/lang/Runnable;Ljava/lang/Throwable;)V");
        mAfter.insertBefore("{" +
                "if($1 instanceof fk.prof.TracingRunnable) {" +
                    "((fk.prof.TracingRunnable)$1).afterExecute($2);" +
                "}" +
            "}");

        return true;
    }

    public boolean transformJettyQueuedThreadPool(CtClass cclass) throws NotFoundException, CannotCompileException {
        CtClass runner = null;

        for (CtClass nestedClass : cclass.getNestedClasses()) {
            if (nestedClass.subtypeOf(runnableClass)) {
                runner = nestedClass;
                break;
            }
        }

        if (runner == null) {
            throw new NotFoundException("Expected a nested runnable class in " + cclass.getName());
        }

        CtMethod mRun = Util.getMethod(runner, "run", "()V");
        mRun.addLocalVariable(localThreadVar, threadClass);

        // get the current thread
        mRun.insertBefore(String.format("%s = java.lang.Thread.currentThread();", localThreadVar));
        // set the current thread into the task
        mRun.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if ("runJob".equals(m.getMethodName())) {
                    String code = String.format("{" +
                            "if($1 instanceof fk.prof.TracingRunnable) {" +
                                "((fk.prof.TracingRunnable)$1).setExecutingThread(%s);" +
                            "}" +
                            "$proceed($$);" +
                        "}", localThreadVar);
                    m.replace(code);
                }
            }
        });

        CtMethod mExecute = Util.getMethod(cclass, "execute", "(Ljava/lang/Runnable;)V");
        mExecute.insertBefore("$1 = fk.prof.TracingRunnable.wrap($1);");

        return true;
    }

    public boolean transformJettyHttpConnection(CtClass cclass) throws NotFoundException, CannotCompileException,
        ClassNotFoundException {
        CtMethod mOnFillable = Util.getMethod(cclass, "onFillable", "()V");

        ClassLoader.getSystemClassLoader().loadClass("org.eclipse.jetty.server.HttpChannelState");

        mOnFillable.insertBefore(String.format("{" +
//                "org.eclipse.jetty.server.HttpChannelState $$$_channel_state = _channel.getState();" +
                // if httpTask is null, it is a request starting. so create a new task.
                "if(_channel.getState().%1$s == null) { " +
                    "_channel.getState().%1$s = new fk.prof.HttpTask();" +
                    "_channel.getState().%1$s.startRead();" +
                "}" +
            "}", hcsHttpTask));

        return true;
    }

    public boolean transformJettyHttpChannelState(CtClass cclass) throws NotFoundException, CannotCompileException {
        // instrument request dispatch start
        CtMethod mhandling = Util.getMethod(cclass,
            "handling",
            "()Lorg/eclipse/jetty/server/HttpChannelState$Action;");
        mhandling.insertAfter(String.format("{" +
                "System.out.println(\"running instrumented finally block of handling method\");" +
                "if(org.eclipse.jetty.server.HttpChannelState$Action.DISPATCH == $_) {" +
                    "%s.startProcess(this.getBaseRequest().getMethod() + \" \" + this.getBaseRequest().getRequestURI" +
            "());" +
                "}" +
            "}", hcsHttpTask), true);

        // instrument request dispatch finish
        CtMethod mUnHandle = Util.getMethod(cclass,
            "unhandle",
            "()Lorg/eclipse/jetty/server/HttpChannelState$Action;");
        mUnHandle.insertBefore(String.format("{" +
                "if(org.eclipse.jetty.server.HttpChannelState$State.DISPATCHED == _state) {" +
                    "%s.endProcess();" +
                "}" +
            "}", hcsHttpTask));

        // instrument request completed
        CtMethod mOnComplete = Util.getMethod(cclass, "onComplete", "()V");
        mOnComplete.insertAfter(String.format("{" +
                "if(org.eclipse.jetty.server.HttpChannelState$State.COMPLETED == _state) {" +
                    "%s.complete();" +
                "}" +
            "}", hcsHttpTask), true);

        // instrument recycle
        CtMethod mRecycle = Util.getMethod(cclass, "recycle", "()V");
        mRecycle.insertBefore(String.format("{" +
                "%s = new fk.prof.HttpTask();" +
            "}", hcsHttpTask));

        cclass.debugWriteFile("/home/gaurav.ashok/Documents/work/temp");
        return true;
    }

    public boolean transformJettyHttpChannelStateMethods(CtClass cclass) throws NotFoundException,
        CannotCompileException {
        instrumentJettyHttpChannelStateMethod(cclass,
            "handling",
            "()Lorg/eclipse/jetty/server/HttpChannelState$Action;");

        instrumentJettyHttpChannelStateMethod(cclass,
            "startAsync",
            "(Lorg/eclipse/jetty/server/AsyncContextEvent;)V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "asyncError",
            "(Ljava/lang/Throwable;)V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "unhandle",
            "()Lorg/eclipse/jetty/server/HttpChannelState$Action;");

        instrumentJettyHttpChannelStateMethod(cclass,
            "dispatch",
            "(Ljavax/servlet/ServletContext;Ljava/lang/String;)V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onTimeout",
            "()V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "complete",
            "()V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "errorComplete",
            "()V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onError",
            "(Ljava/lang/Throwable;)V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onComplete",
            "()V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "recycle",
            "()V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onReadUnready",
            "()V");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onContentAdded",
            "()Z");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onReadReady",
            "()Z");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onReadPossible",
            "()Z");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onReadEof",
            "()Z");

        instrumentJettyHttpChannelStateMethod(cclass,
            "onWritePossible",
            "()Z");

        return true;
    }

    private void instrumentJettyHttpChannelStateMethod(CtClass ctClass, String methodName, String methodSig) throws
        NotFoundException, CannotCompileException {
        CtMethod m = Util.getMethod(ctClass, methodName, methodSig);

        m.insertBefore(String.format("fk.prof.bciagent.tracer.GenericTracer.trace(\"before %s\", this);",
            methodName));
        m.insertAfter(String.format("fk.prof.bciagent.tracer.GenericTracer.trace(\"after %s\", this);",
            methodName), true);
    }
}
