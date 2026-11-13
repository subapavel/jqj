package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Truth;

/**
 * {@code if c then t else e end} (ADR-0037): each result of the condition runs one branch on the If's input, {@code t}
 * when the truth holds for it and {@code e} when it does not, and a stop in a branch ends the condition too. No step of
 * its own.
 */
public final class IfOp extends Op {
  private final Op cond;
  private final Op then;
  private final Op otherwise;
  private final Truth truth;

  public IfOp(Op cond, Op then, Op otherwise, Truth truth) {
    this.cond = cond;
    this.then = then;
    this.otherwise = otherwise;
    this.truth = truth;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return cond.run(state, slots, in, v -> (truth.test(v) ? then : otherwise).run(state, slots, in, out));
  }
}
