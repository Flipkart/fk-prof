package fk.prof.bciagent;

import javassist.CtConstructor;

@FunctionalInterface
public interface ConstructorBciHook {
    void apply(CtConstructor constructor) throws Exception;
}