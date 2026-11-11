package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code .name} (#34 design section 6): on an object the last value of the key (ADR-0002), or null when there is
 * none; on null, null; on any other kind a {@link FilterException} with jq's text. The name is one the lexer takes
 * ({@code [A-Za-z_][A-Za-z0-9_]*}), so its JSON text needs no escape and its chars are its bytes.
 */
public final class Field implements Fn0 {
  /** jq-ref 1.8.2, recorded (field/error-name-N): a name of at most 27 bytes is shown whole, a longer one cut. */
  private static final int WHOLE = 27;

  /** A longer name shows this many bytes, then {@code ...}. */
  private static final int SHOWN = 24;

  private final String name;

  /** The name as jq's error text shows it: in quotes, cut when long. */
  private final String shown;

  public Field(String name) {
    this.name = name;
    this.shown = "\"" + (name.length() <= WHOLE ? name : name.substring(0, SHOWN) + "...") + "\"";
  }

  @Override
  public Object apply(Object in) {
    if (in instanceof ObjectView object) {
      JqValue value = object.get(name);
      return value == null ? JqNull.NULL : value;
    }
    if (in == JqNull.NULL) {
      return in;
    }
    throw new FilterException(BuiltString.of("Cannot index " + kind(in) + " with string (" + shown + ")"));
  }

  /** jq's word for the kind of {@code in}, which is neither an object nor null: those are answered above. */
  private static String kind(Object in) {
    if (in instanceof JqArray) {
      return "array";
    }
    if (in instanceof JqString) {
      return "string";
    }
    // The six kinds are sealed (ADR-0016): after object, null, array and string, a value that is no number is a boolean.
    return in instanceof JqNumber ? "number" : "boolean";
  }
}
