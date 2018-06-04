package fk.prof.bciagent;

import javassist.CtClass;

@FunctionalInterface
interface ClassInstrumentor {

    boolean apply(CtClass cclass) throws Exception ;
}
