package fk.prof;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @understands calling test-jni functions
 */
public class TestJni {
    private static final AtomicBoolean loaded = new AtomicBoolean(false);

    public static void loadJniLib() throws Exception {
        if (loaded.compareAndSet(false, true)) {
            String libName = "build/libtestjni" + Platforms.getDynamicLibraryExtension();
            File lib = new File(libName);
            if(!lib.exists()) {
                lib = new File("build/" + libName);
                if(!lib.exists()) {
                    throw new FileNotFoundException(libName);
                }
            }

            System.load(lib.getAbsolutePath());
        }
    }
    
    public native void setupPerfCtx();
    public native void teardownPerfCtx();
    public native void setupThdTracker();
    public native void teardownThdTracker();
    public native int getCurrentCtx(long[] fill);
    public native String getCtxName(long ctxid);
    public native int getCtxCov(long ctxid);
    public native int getCtxMergeSemantic(long ctxid);
    public native boolean isGenerated(long ctxid);
    public native String getDefaultCtxName();
    public native String getUnknownCtxName();
}
