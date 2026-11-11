package io.github.subapavel.jqj.value;

import io.github.subapavel.jqj.internal.value.ArrayView;

/** A jq array (ADR-0016). */
public sealed interface JqArray extends JqValue permits ArrayView {
  /** The number of elements. */
  int length();

  /** The element at {@code index}. */
  JqValue get(int index);
}
