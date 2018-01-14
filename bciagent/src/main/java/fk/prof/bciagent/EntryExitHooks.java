package fk.prof.bciagent;

class EntryExitHooks<T> {
  final BciHook<T> entry;
  final BciHook<T> exit;

  EntryExitHooks(BciHook<T> entry, BciHook<T> exit) {
    this.entry = entry;
    this.exit = exit;
  }
}
