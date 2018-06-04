package fk.prof.bciagent;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

public class Util {
    public static CtMethod getMethod(CtClass clazz, String name, String desc) throws NotFoundException {
        for(CtMethod m : clazz.getDeclaredMethods(name)) {
            if(m.getSignature().equals(desc)) {
                return m;
            }
        }
        throw new NotFoundException(name + "#" + desc + " not found");
    }
}
