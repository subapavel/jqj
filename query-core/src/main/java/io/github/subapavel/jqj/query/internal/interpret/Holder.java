package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.Sink;

/**
 * The sink a tail emitter runs into (ADR-0043): it holds the one result until {@link #take} and never stops, so the run
 * that fills it has returned before its taker goes on.
 */
final class Holder implements Sink {
  /** What {@link #take} gives when no result came. */
  static final Object EMPTY = new Object();

  private Object value = EMPTY;

  @Override
  public boolean emit(Object v) {
    value = v;
    return true;
  }

  /** The result that came since the last take, or {@link #EMPTY}; the holder is empty again. */
  Object take() {
    Object v = value;
    value = EMPTY;
    return v;
  }
}
