package io.github.subapavel.jqj.internal.op;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.json.Printer;
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
 * jq's {@code .name} and {@code ."string"} (#34 design section 6, #61): on an object the last value of the key
 * (ADR-0002), or null when there is none; on null, null; on any other kind a {@link FilterException} with jq's text.
 */
public final class Field implements Fn0 {
  /**
   * jq's {@code jv_dump_string_trunc} into its 30-byte buffer (ADR-0015): a dump of at most 29 bytes is shown whole
   * (jq-ref 1.8.2, recorded: field/error-name-N, a name of at most 27 bytes).
   */
  private static final int WHOLE = 29;

  /** A longer dump keeps at most this many bytes, stepped back to a UTF-8 boundary, then {@code ..."}. */
  private static final int KEPT = 25;

  private final String name;

  /** The key as jq's error text shows it: its JSON text, cut when long. */
  private final String shown;

  public Field(String name) {
    this.name = name;
    byte[] dump = Printer.json(BuiltString.of(name)).getBytes(UTF_8);
    if (dump.length <= WHOLE) {
      this.shown = new String(dump, UTF_8);
    } else {
      // The first byte not kept starts a character, as jq's jvp_utf8_backtrack steps back (observed on jq-ref 1.8.2,
      // 2026-11-12: the key "abcdefghijklmnopqrstuvw" + "éééé" on "x" shows ("abcdefghijklmnopqrstuvw...")).
      int kept = KEPT;
      while ((dump[kept] & 0xC0) == 0x80) {
        kept--;
      }
      this.shown = new String(dump, 0, kept, UTF_8) + "...\"";
    }
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
