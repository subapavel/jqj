package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call of a {@link Many} as a loop over its cursor (ADR-0034): one step per result, before its emit; a stop ends the
 * loop at once, with no further result or step.
 */
public final class IntrinsicManyOp extends Op {
  /** The arguments of every call with none: an empty array has nothing to write, so the calls share it. */
  private static final Object[] NO_ARGS = {};

  private final Many many;
  private final Argument[] args;

  public IntrinsicManyOp(Many many, Argument[] args) {
    this.many = many;
    this.args = args;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] values = NO_ARGS;
    if (args.length > 0) {
      values = new Object[args.length];
      for (int i = 0; i < values.length; i++) {
        values[i] = args[i].read(slots, in);
      }
    }
    for (long at = many.first(state, in, values); at != Many.END; at = many.next(state, in, values, at)) {
      state.tick();
      if (!out.emit(many.value(state, in, at))) {
        return false;
      }
    }
    return true;
  }
}
