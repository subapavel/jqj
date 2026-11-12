package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A node of the interpreter tree, in push form only (ADR-0008, ADR-0018). */
public abstract sealed class Op permits IdentityOp, PipeOp, IntrinsicOp {
  Op() {}

  /**
   * Pushes the results for {@code in} into {@code out}, in the run {@code state} belongs to (ADR-0030): true when they
   * ran out, false when out stopped.
   */
  public abstract boolean run(RunState state, Object in, Sink out);
}
