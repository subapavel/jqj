package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.json.ErrorDump;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code .name} and {@code ."string"} (#34 design section 6, #61): on an object the last value of the key
 * (ADR-0002), or null when there is none, charging one tick per key scanned (ADR-0034); on null, null; on any other
 * kind a {@link FilterException} with jq's text, or no result for the quiet variant {@code .name?} (ADR-0034).
 */
public final class Field implements Fn0 {
  private final String name;

  /** Whether this is {@code .name?}, which drops its own error. */
  private final boolean quiet;

  /** The key as jq's error text shows it: its JSON text, cut when long (jq-ref 1.8.2, recorded: field/error-name-N). */
  private final String shown;

  public Field(String name) {
    this(name, false);
  }

  public Field(String name, boolean quiet) {
    this.name = name;
    this.quiet = quiet;
    this.shown = ErrorDump.of(BuiltString.of(name));
  }

  @Override
  public Object apply(RunState state, Object in) {
    return switch ((JqValue) in) {
      case ObjectView object -> {
        JqValue value = object.get(state, name);
        yield value == null ? JqNull.NULL : value;
      }
      case JqNull nul -> nul;
      case JqValue other -> {
        if (quiet) {
          yield CallForm.NONE;
        }
        throw Errors.cannotIndex(other, "string", shown);
      }
    };
  }
}
