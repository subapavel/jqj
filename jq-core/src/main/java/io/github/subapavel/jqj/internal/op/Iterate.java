package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code .[]} (jq-ref 1.8.2, recorded: diff/iterate.rec): an array's elements in order; an object's values, each
 * key at its first position with its last value (ADR-0002); on any other kind jq's error, which the quiet variant
 * {@code .[]?} drops (ADR-0034). The cursor is a view's tape index or a built array's index; each step after a result
 * checks the document again, since the sink that took the result may have read another one onto the tape (ADR-0026).
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
    return switch ((JqValue) in) {
      case ArrayView view -> view.first();
      case BuiltArray built -> built.length() > 0 ? 0 : END;
      case ObjectView object -> object.firstKey();
      case JqValue other -> {
        if (quiet) {
          yield END;
        }
        throw NavigationErrors.cannotIterate(other);
      }
    };
  }

  /** Only a value {@link #first} gave a cursor for comes here: an array or an object. */
  @Override
  public long next(RunState state, Object in, Object[] args, long cursor) {
    if (in instanceof ArrayView view) {
      return view.next((int) cursor);
    }
    if (in instanceof BuiltArray built) {
      return cursor + 1 < built.length() ? cursor + 1 : END;
    }
    return ((ObjectView) in).nextKey((int) cursor);
  }

  @Override
  public Object value(RunState state, Object in, long cursor) {
    if (in instanceof ArrayView view) {
      return view.at((int) cursor);
    }
    if (in instanceof BuiltArray built) {
      return built.get((int) cursor);
    }
    return ((ObjectView) in).valueOfKey((int) cursor);
  }
}
