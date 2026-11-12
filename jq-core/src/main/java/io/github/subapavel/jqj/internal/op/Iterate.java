package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.Members;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code .[]} (jq-ref 1.8.2, recorded: diff/iterate.rec): the {@link Members} of an array or an object, in order;
 * on any other kind jq's error, which the quiet variant {@code .[]?} drops (ADR-0034). The cursor is the container's.
 */
public final class Iterate implements Many {
  /** {@code .[]}. */
  public static final Operation PLAIN = new Operation(".[]", new Iterate(false));

  /** {@code .[]?}. */
  public static final Operation QUIET = new Operation(".[]?", new Iterate(true));

  /** Whether this is {@code .[]?}, which drops its own error. */
  private final boolean quiet;

  private Iterate(boolean quiet) {
    this.quiet = quiet;
  }

  @Override
  public long first(RunState state, Object in, Object[] args) {
    JqValue v = (JqValue) in;
    return switch (v) {
      case Members members -> members.first();
      case JqNull _, JqBool _, JqNumber _, JqString _ -> {
        if (quiet) {
          yield END;
        }
        throw NavigationErrors.cannotIterate(v);
      }
    };
  }

  /** {@link #first} gives a cursor only to {@link Members}, so only they come here. */
  @Override
  public long next(RunState state, Object in, Object[] args, long cursor) {
    return ((Members) in).next(state, (int) cursor);
  }

  /** {@link #first} gives a cursor only to {@link Members}, so only they come here. */
  @Override
  public Object value(RunState state, Object in, long cursor) {
    return ((Members) in).member(state, (int) cursor);
  }
}
