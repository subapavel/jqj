package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.Collector;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import java.util.Arrays;

/**
 * Gathers its body's results and emits what the collector makes of them (ADR-0034 amendment). It charges the
 * container before its first allocation and each element before it stores it (ADR-0035): a collector charges per
 * element, never the capacity it grows to, and its growth copies charge no step.
 */
public final class CollectOp extends Op {
  /** The capacity of the first array, allocated with the first result. */
  private static final int FIRST = 4;

  /** The results of a body that gave none: an empty array has nothing to write, so the collectors share it. */
  private static final Object[] NONE = {};

  private final Op body;
  private final Collector collector;

  public CollectOp(Op body, Collector collector) {
    this.body = body;
    this.collector = collector;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    state.chargeBuilt(Charges.CONTAINER);
    Elements elements = new Elements(state);
    // The body's sink never stops, so the body always runs out.
    body.run(state, slots, in, elements);
    return out.emit(collector.collect(elements.values, elements.length));
  }

  /** The results so far, {@code [0, length)} of {@code values}. */
  private static final class Elements implements Sink {
    private final RunState state;
    private Object[] values = NONE;
    private int length;

    Elements(RunState state) {
      this.state = state;
    }

    @Override
    public boolean emit(Object v) {
      state.chargeBuilt(Charges.ELEMENT);
      if (length == values.length) {
        values = Arrays.copyOf(values, Math.max(FIRST, length << 1));
      }
      values[length++] = v;
      return true;
    }
  }
}
