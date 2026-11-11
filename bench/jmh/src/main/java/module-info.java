// jmh-core 1.37 has no Automatic-Module-Name, so jmh.core is its file name; the bench runs on the class path
// (ADR-0013), where the name is never resolved.
@SuppressWarnings("requires-automatic")
module io.github.subapavel.jqj.bench {
  requires io.github.subapavel.jqj;
  requires jmh.core;
}
