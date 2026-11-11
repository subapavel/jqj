package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqString;
import java.lang.foreign.MemorySegment;

/**
 * A string built at run time (#34 design section 7), such as jq's error texts: UTF-8 bytes {@code [offset, offset +
 * length)} of {@code bytes}. Public only so the kind can permit it.
 */
public final class BuiltString implements JqString {
  private final byte[] bytes;
  private final int offset;
  private final int length;

  /**
   * The ownership mark of ADR-0022: every built class carries it from the ticket that creates the class, and it stays
   * null until spec 3 (spec-1 grilling round 1, item 9).
   */
  private final Object owner;

  private BuiltString(byte[] bytes, int offset, int length) {
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
    this.owner = null;
  }

  /** The string of {@code text}'s UTF-8 bytes. */
  public static BuiltString of(String text) {
    byte[] utf8 = text.getBytes(UTF_8);
    return new BuiltString(utf8, 0, utf8.length);
  }

  /** jq's compact text needs jq's re-escape, which is #35's. */
  @Override
  public String toJson() {
    throw Stub.unbuilt("the JSON text of a built string");
  }

  @Override
  public String value() {
    return new String(bytes, offset, length, UTF_8);
  }

  /** A read-only slice of the bytes, without a copy. */
  @Override
  public MemorySegment utf8() {
    return MemorySegment.ofArray(bytes).asSlice(offset, length).asReadOnly();
  }
}
