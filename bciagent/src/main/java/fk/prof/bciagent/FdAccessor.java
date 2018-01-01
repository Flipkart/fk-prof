package fk.prof.bciagent;

import java.io.FileDescriptor;
import java.lang.reflect.Field;

public class FdAccessor {
    private static Field fdField;
    private static boolean initialised = false;

    static {
        try {
            fdField = FileDescriptor.class.getDeclaredField("fd");
            if(fdField != null) {
                fdField.setAccessible(true);
            }
            initialised = true;
        }
        catch (NoSuchFieldException e) {
        }
    }

    static int getFd(FileDescriptor fdObj) {
        try {
            return fdField.getInt(fdObj);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("fd field should have been accessible");
        }
    }

    static boolean isInitialised() {
        return initialised;
    }
}
