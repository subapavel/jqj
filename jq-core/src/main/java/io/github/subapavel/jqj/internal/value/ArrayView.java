package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqValue;

/** An array of a document (ADR-0016). Public only so the kind can permit it; every method is a Stub. */
public final class ArrayView implements JqArray {
  ArrayView() {}

  @Override
  public String toJson() {
    throw Stub.unbuilt("JqArray.toJson");
  }

  @Override
  public int length() {
    throw Stub.unbuilt("JqArray.length");
  }

  @Override
  public JqValue get(int index) {
    throw Stub.unbuilt("JqArray.get");
  }
}
