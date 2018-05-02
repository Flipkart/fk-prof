package fk.prof.bciagent;

@FunctionalInterface
interface BciHook<T> {
  void apply(T method) throws Exception;
}