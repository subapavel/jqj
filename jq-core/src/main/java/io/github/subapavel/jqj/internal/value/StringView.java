package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;

/** A string of a document (ADR-0016). Public only so the kind can permit it; every method is a Stub. */
public final class StringView implements JqString {
  StringView() {}

  @Override
  public String toJson() {
    throw Stub.unbuilt("JqString.toJson");
  }

  @Override
  public String value() {
    throw Stub.unbuilt("JqString.value");
  }

  @Override
  public MemorySegment utf8() {
    throw Stub.unbuilt("JqString.utf8");
  }
}
