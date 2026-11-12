package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's {@code jv_dump_string_trunc} into its 30-byte buffer (ADR-0015): what an error text shows of a value. A
 * compact text of at most 29 bytes is shown whole. A longer one is cut to 29 bytes: a string keeps 25 bytes and ends
 * in {@code ..."}, an array in {@code ...]} and an object in {@code ...}}; a number keeps 26 bytes and ends in
 * {@code ...}. The cut steps back to the start of a character, as jq's {@code jvp_utf8_backtrack} does (jq-ref 1.8.2,
 * recorded: index/array-key-*, iterate/cut-*; observed 2026-11-12: an array key on a string shows
 * {@code [1111111111,2222222222,33...]}, an object key on an object {@code {"aaaaaaaaaaaaaaaaaaaaa":...}}).
 */
public final class ErrorDump {
  /** The longest text shown whole: jq's buffer less its NUL. */
  private static final int WHOLE = 29;

  private ErrorDump() {}

  /** What an error text shows of {@code v}. */
  public static String of(JqValue v) {
    int closing =
        switch (v) {
          case JqString _ -> '"';
          case JqArray _ -> ']';
          case JqObject _ -> '}';
          // A number; null and the booleans are never longer than five bytes.
          default -> 0;
        };
    return of(Printer.head(v, WHOLE + 1), closing);
  }

  /**
   * What an error text shows of a compact text whose first bytes are {@code dump}, all of them when it has at most
   * {@link #WHOLE}, otherwise at least one more; {@code closing} is the bracket or quote a cut ends with, 0 for none.
   */
  public static String of(byte[] dump, int closing) {
    if (dump.length <= WHOLE) {
      return new String(dump, UTF_8);
    }
    String tail = closing == 0 ? "..." : "..." + (char) closing;
    int kept = WHOLE - tail.length();
    // The first byte not kept starts a character.
    while ((dump[kept] & 0xC0) == 0x80) {
      kept--;
    }
    return new String(dump, 0, kept, UTF_8) + tail;
  }
}
