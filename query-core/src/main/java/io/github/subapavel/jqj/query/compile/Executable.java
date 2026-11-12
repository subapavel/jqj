package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A compiled tree (GLOSSARY "Executable"): immutable, and it keeps nothing from one run to the next. Each run gets its
 * own slots (ADR-0034), so two runs on two threads share nothing.
 */
public final class Executable {
  private final Op root;

  /** The number of slots a run needs. */
  private final int slots;

  Executable(Op root, int slots) {
    this.root = root;
    this.slots = slots;
  }

  /**
   * Pushes the results for {@code in} into {@code out} (ADR-0008), in the run {@code state} belongs to (ADR-0030):
   * true when they ran out, false when out stopped.
   */
  public boolean run(RunState state, Object in, Sink out) {
    return root.run(state, new Object[slots], in, out);
  }
}
