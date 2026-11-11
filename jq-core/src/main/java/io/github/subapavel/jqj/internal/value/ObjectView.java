package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * An object of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * Its entries alternate key and value. The tape keeps duplicate keys, so every member checks them again, as jq does:
 * a key keeps its first position and its last value (ADR-0002). That check compares each key with the earlier ones,
 * so {@link #size()} and {@link #forEach} take time quadratic in the number of keys. Every key a member meets must be
 * a tag-5 string until #35 decodes tag 6. The writer walks the keys with the same pair, {@link #isFirst} and
 * {@link #lastValueOf}.
 */
public record ObjectView(Document doc, int index) implements JqObject {

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public int size() {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = Tape.end(tape, index);
    int size = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (isFirst(tape, bytes, index, k)) {
        size++;
      }
    }
    return size;
  }

  /** The value of the last {@code key}, or Java {@code null} when the object has no such key (ADR-0026). */
  @Override
  public JqValue get(String key) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = Tape.end(tape, index);
    int value = 0; // none yet: index 0 is the root, never a member's value
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      StringView.requirePlain(tape[k]);
      if (spells(bytes, tape[k], key)) {
        value = k + 1;
      }
    }
    return value == 0 ? null : doc.valueAt(tape, value);
  }

  /**
   * Each key at its first position with its last value; no call is made when a key is a Stub. Each step after a
   * call checks the document again: the action may have read another document onto this tape (ADR-0016, STALE_VIEW).
   */
  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = Tape.end(tape, index);
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (isFirst(tape, bytes, index, k)) {
        // lastValueOf checks every later key, so the first call comes after all keys are checked.
        int value = lastValueOf(tape, bytes, k, end);
        action.accept(StringView.text(bytes, tape[k]), doc.valueAt(tape, value));
        tape = doc.live();
      }
    }
  }

  /**
   * Whether the key at {@code k} of the object at {@code object} is tag 5 and no earlier key equals it; a tag-6 key is
   * a Stub. The keys before {@code k} must have been checked already.
   */
  public static boolean isFirst(long[] tape, byte[] bytes, int object, int k) {
    StringView.requirePlain(tape[k]);
    int first = object + 1;
    while (!sameKey(tape, bytes, first, k)) {
      first = Tape.next(tape, first + 1);
    }
    return first == k;
  }

  /**
   * The value of the last key equal to the key at {@code k}, checking each later key on the way; {@code end} is the
   * object's {@link Tape#end}.
   */
  public static int lastValueOf(long[] tape, byte[] bytes, int k, int end) {
    int value = k + 1;
    for (int later = Tape.next(tape, k + 1); later < end; later = Tape.next(tape, later + 1)) {
      StringView.requirePlain(tape[later]);
      if (sameKey(tape, bytes, later, k)) {
        value = later + 1;
      }
    }
    return value;
  }

  /** Two tag-5 keys are equal when their bytes are, quotes included. */
  private static boolean sameKey(long[] tape, byte[] bytes, int a, int b) {
    int from = Tape.off(tape[a]);
    int other = Tape.off(tape[b]);
    return Arrays.equals(bytes, from, from + Tape.span(tape[a]), bytes, other, other + Tape.span(tape[b]));
  }

  /**
   * Whether the tag-5 key {@code entry} spells {@code key}, compared by code point over its UTF-8 bytes. A lone
   * surrogate is no code point, so a key holding one never matches; encoding {@code key} with a charset instead would
   * turn it into {@code ?} and find the key {@code "?"}.
   */
  private static boolean spells(byte[] bytes, long entry, String key) {
    int p = Tape.off(entry) + 1;
    int end = Tape.off(entry) + Tape.span(entry) - 1;
    int n = key.length();
    for (int i = 0; i < n; i++) {
      char c = key.charAt(i);
      int codePoint = c;
      if (Character.isSurrogate(c)) {
        if (!Character.isHighSurrogate(c) || i + 1 == n || !Character.isLowSurrogate(key.charAt(i + 1))) {
          return false;
        }
        codePoint = Character.toCodePoint(c, key.charAt(++i));
      }
      p = match(bytes, p, end, codePoint);
      if (p < 0) {
        return false;
      }
    }
    return p == end;
  }

  /** Where the UTF-8 form of {@code codePoint} ends if {@code bytes[p, end)} starts with it, otherwise -1. */
  private static int match(byte[] bytes, int p, int end, int codePoint) {
    int length = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    if (end - p < length) {
      return -1;
    }
    // The lead byte: the code point alone below 0x80, otherwise 110, 1110 or 11110 and its top bits.
    int shift = 6 * (length - 1);
    int lead = length == 1 ? codePoint : ((0xF00 >> length) & 0xFF) | (codePoint >> shift);
    if ((bytes[p] & 0xFF) != lead) {
      return -1;
    }
    for (int i = 1; i < length; i++) {
      shift -= 6;
      if ((bytes[p + i] & 0xFF) != (0x80 | ((codePoint >> shift) & 0x3F))) {
        return -1;
      }
    }
    return p + length;
  }
}
