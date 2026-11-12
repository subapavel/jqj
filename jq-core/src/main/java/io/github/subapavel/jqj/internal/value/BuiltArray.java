package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;

/**
 * An array built at run time (#63, decision 1 of its approval), such as a slice's: elements {@code [0, length)} of
 * {@code elements}, each a view or a built value, never written once the array is made. Public only so the kind can
 * permit it. Equality is the object's, never jq's (ADR-0026).
 */
public final class BuiltArray implements JqArray, Members {
  private final JqValue[] elements;
  private final int length;

  /**
   * The ownership mark of ADR-0022: every built class carries it from the ticket that creates the class, and it stays
   * null until spec 3 (spec-1 grilling round 1, item 9).
   */
  private final Object owner;

  /** The array of {@code elements}, which it keeps: the caller never writes the array again. */
  public BuiltArray(JqValue[] elements) {
    this.elements = elements;
    this.length = elements.length;
    this.owner = null;
  }

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(this);
  }

  @Override
  public int length() {
    return length;
  }

  /** @throws IndexOutOfBoundsException unless {@code 0 <= i < length()} (ADR-0026) */
  @Override
  public JqValue get(int i) {
    return elements[i];
  }

  /**
   * The elements {@code [from, to)}, {@code 0 <= from <= to <= length()}, as a new array, charging {@code state} one tick
   * per element copied (ADR-0034).
   */
  public BuiltArray slice(RunState state, int from, int to) {
    state.tick(to - from);
    return new BuiltArray(Arrays.copyOfRange(elements, from, to));
  }

  /** The cursor of the first element, its index, or -1 for an empty array. */
  @Override
  public int first() {
    return length > 0 ? 0 : -1;
  }

  /** The cursor after the element at {@code i}, or -1 after the last. */
  @Override
  public int next(RunState state, int i) {
    return i + 1 < length ? i + 1 : -1;
  }

  /** The element at {@code i}. */
  @Override
  public JqValue member(RunState state, int i) {
    return elements[i];
  }
}
