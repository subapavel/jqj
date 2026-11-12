package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
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

  /** One case per kind of the sealed {@link JqValue} (ADR-0016), so the switch needs no default. */
  @Override
  public Object apply(Object in) {
    return switch ((JqValue) in) {
      case JqObject object -> {
        JqValue value = object.get(name);
        yield value == null ? JqNull.NULL : value;
      }
      case JqNull nul -> nul;
      case JqArray _ -> throw cannotIndex("array");
      case JqString _ -> throw cannotIndex("string");
      case JqNumber _ -> throw cannotIndex("number");
      case JqBool _ -> throw cannotIndex("boolean");
    };
  }

  /** jq's error for {@code .name} on a value of the kind jq calls {@code kind}. */
  private FilterException cannotIndex(String kind) {
    return new FilterException(BuiltString.of("Cannot index " + kind + " with string (" + shown + ")"));
  }
}
