package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A compiled tree (GLOSSARY "Executable"): immutable, and it keeps nothing from one run to the next. */
public final class Executable {
  private final Op root;

  Executable(Op root) {
    this.root = root;
  }

  /**
   * Pushes the results for {@code in} into {@code out} (ADR-0008), in the run {@code state} belongs to (ADR-0030):
   * true when they ran out, false when out stopped.
   */
  public boolean run(RunState state, Object in, Sink out) {
    return root.run(state, in, out);
  }
}
