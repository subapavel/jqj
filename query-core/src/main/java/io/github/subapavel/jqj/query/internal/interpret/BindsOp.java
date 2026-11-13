package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code v1 as $s1 | … | vn as $sn | body} (ADR-0043), run as the chain of nested {@link BindOp}s it means. A tail
 * value runs into a {@link Holder}, sets its slot and the loop goes on to the next value; with no result the Binds
 * runs out there. Any other value runs the rest, the later values and the body, inside its emit, as a BindOp does. So
 * the stack grows by one recursion per generator value, not per value. Each value and the body run on the Binds' own
 * input; no step of its own.
 */
public final class BindsOp extends Op {
  private final Op[] values;

  /** Whether each value is a tail emitter: one result at most, and it never breaks out (ADR-0043). */
  private final boolean[] tails;

  private final int[] slots;
  private final Op body;

  public BindsOp(Op[] values, boolean[] tails, int[] slots, Op body) {
    this.values = values;
    this.tails = tails;
    this.slots = slots;
    this.body = body;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return from(0, state, slots, in, out);
  }

  /** The values from {@code first} on, then the body, with the slots of the values before {@code first} set. */
  private boolean from(int first, RunState state, Object[] slots, Object in, Sink out) {
    Holder holder = new Holder();
    for (int k = first; k < values.length; k++) {
      int slot = this.slots[k];
      if (!tails[k]) {
        int next = k + 1;
        return values[k].run(
            state,
            slots,
            in,
            v -> {
              slots[slot] = v;
              return from(next, state, slots, in, out);
            });
      }
      // The holder never stops and a tail emitter never breaks out, so this run's answer is always true.
      values[k].run(state, slots, in, holder);
      Object v = holder.take();
      if (v == Holder.EMPTY) {
        return true;
      }
      slots[slot] = v;
    }
    return body.run(state, slots, in, out);
  }
}
