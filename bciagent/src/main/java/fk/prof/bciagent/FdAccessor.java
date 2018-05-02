package fk.prof.bciagent;

import java.io.FileDescriptor;
import java.lang.reflect.Field;

public class FdAccessor {
    private static Field fdField;
    private static boolean initialized = false;

    static {
        try {
            fdField = FileDescriptor.class.getDeclaredField("fd");
            if(fdField != null) {
                fdField.setAccessible(true);
            }
            initialized = true;
        }
        catch (NoSuchFieldException e) {
        }
    }

    public static int getFd(FileDescriptor fdObj) {
        try {
            return fdField.getInt(fdObj);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("fd field should have been accessible");
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
