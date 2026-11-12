package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's {@code .[k]}, its {@code jv_get} (jq-ref, recorded: diff/index.rec, slice.rec): an object and a string key
 * give the key's last value or null; an array and a number give the element, or null for NaN or past either end, a
 * fraction cut toward zero and a negative index counted from the end; an array or a string and an object key give the
 * slice of the key's {@code "start"} and {@code "end"} ({@link Slice}); null and a string, number or object key give
 * null. Any other pair is jq's error, which the quiet variant {@code .[k]?} drops (ADR-0034). An array key on an array
 * ({@code jv_array_indexes}) is a run-time Stub until #72, never dropped.
 */
public final class Index implements Fn1 {
  /** {@code .[k]}. */
  public static final Operation PLAIN = new Operation(".[k]", new Index(false));

  /** {@code .[k]?}. */
  public static final Operation QUIET = new Operation(".[k]?", new Index(true));

  /** Whether this is {@code .[k]?}, which drops its own error. */
  private final boolean quiet;

  private Index(boolean quiet) {
    this.quiet = quiet;
  }

  @Override
  public Object apply(RunState state, Object in, Object key) {
    JqValue t = (JqValue) in;
    JqValue k = (JqValue) key;
    if (t instanceof ObjectView object && k instanceof JqString name) {
      JqValue value = object.get(state, name.value());
      return value == null ? JqNull.NULL : value;
    }
    if (t instanceof JqArray array && k instanceof JqNumber n) {
      return element(state, array, n.doubleValue());
    }
    if ((t instanceof JqArray || t instanceof JqString) && k instanceof ObjectView bounds) {
      // parse_slice reads both keys; a missing one is jq's invalid, which is no number.
      JqValue sliced = Slice.of(state, t, bounds.get(state, "start"), bounds.get(state, "end"));
      if (sliced != null) {
        return sliced;
      }
      if (quiet) {
        return CallForm.NONE;
      }
      throw NavigationErrors.sliceIndices();
    }
    if (t instanceof JqArray && k instanceof JqArray) {
      throw Stub.unbuilt("an array index of an array (jq's jv_array_indexes, #72)");
    }
    if (t instanceof JqNull && (k instanceof JqString || k instanceof JqNumber || k instanceof ObjectView)) {
      return t;
    }
    if (quiet) {
      return CallForm.NONE;
    }
    throw NavigationErrors.cannotIndex(t, k);
  }

  /** The element at {@code i} of {@code array}, or null: a view charges the entries it steps (ADR-0034). */
  private static JqValue element(RunState state, JqArray array, double i) {
    if (Double.isNaN(i)) {
      return JqNull.NULL;
    }
    // The cast cuts toward zero and saturates, so an index past the int range is past either end.
    long at = (int) i;
    if (at < 0) {
      at += Slice.length(state, array);
      if (at < 0) {
        return JqNull.NULL;
      }
    }
    JqValue value =
        switch (array) {
          case ArrayView view -> view.get(state, (int) at);
          case BuiltArray built -> at < built.length() ? built.get((int) at) : null;
        };
    return value == null ? JqNull.NULL : value;
  }
}
