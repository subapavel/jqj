package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A call of a one-result operation: its result goes to the sink, whose answer is the run's. */
public final class IntrinsicOp extends Op {
  private final Fn0 fn;

  public IntrinsicOp(Fn0 fn) {
    this.fn = fn;
  }

  @Override
  public boolean run(RunState state, Object in, Sink out) {
    return out.emit(fn.apply(state, in));
  }
}
