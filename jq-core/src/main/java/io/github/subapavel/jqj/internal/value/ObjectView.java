package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * An object of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * Its entries alternate key and value. On a distinct-key object (tag 10, ADR-0002 as amended by F-40) the reader proved
 * no key repeats, so every walk takes each member once and a field's scan stops at its match.
 *
 * <p>An object not proved so (tag 9) may repeat a key, so every member checks again, as jq does: a key keeps its first
 * position and its last value (ADR-0002). That check compares each key with the earlier ones and the later ones, so
 * {@link #size()} and {@link #forEach} take time quadratic in the number of keys there. Keys compare by their decoded
 * bytes (#35): a tag-6 key is decoded at each compare, so {@code "\\u0061"} and {@code "a"} are one key. A filter's
 * {@code .[]} makes the same two scans per member and charges one tick per key each compares, so a tag-9 object of n
 * distinct keys costs n * n + n - 1 ticks with the loop's one per member (ADR-0008, ADR-0034); the writer walks its
 * keys with the same scans, {@link #firstScan} and {@link #lastScan}, and charges them the same way.
 */
public record ObjectView(Document doc, int index) implements JqObject, Members, Keyed {

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public int size() {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    boolean distinct = Tape.tag(tape[index]) == Tape.DISTINCT;
    int end = Tape.end(tape, index);
    int size = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (distinct || isFirst(tape, bytes, index, k)) {
        size++;
      }
    }
    return size;
  }

  /** The value of the last {@code key}, or Java {@code null} when the object has no such key (ADR-0026). */
  @Override
  public JqValue get(String key) {
    return valueAt((int) find(key));
  }

  /**
   * {@link #get(String)} for a filter: one tick per key scanned (ADR-0034, decision 3 of #63's approval). That is up to
   * the match on a distinct-key object, and every key on another, since the last match wins there.
   */
  @Override
  public JqValue get(RunState state, String key) {
    long found = find(key);
    state.tick((int) (found >>> 32));
    return valueAt((int) found);
  }

  /** The keys scanned for {@code key} in the high half; in the low half the tape index of its last value, 0 for none. */
  private long find(String key) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    // A distinct-key object holds at most one match.
    boolean distinct = Tape.tag(tape[index]) == Tape.DISTINCT;
    int end = Tape.end(tape, index);
    int keys = 0;
    int value = 0; // none yet: index 0 is the root, never a member's value
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      keys++;
      if (spells(bytes, tape[k], key)) {
        value = k + 1;
        if (distinct) {
          break;
        }
      }
    }
    return (long) keys << 32 | value;
  }

  /** The value at tape index {@code value}, or Java {@code null} for 0. */
  private JqValue valueAt(int value) {
    return value == 0 ? null : doc.valueAt(doc.live(), value);
  }

  /** The cursor of the first key, its tape index, or -1 for an empty object; the first key needs no scan. */
  @Override
  public int first() {
    long[] tape = doc.live();
    return index + 1 < Tape.end(tape, index) ? index + 1 : -1;
  }

  /**
   * The cursor of the next key at its first position after the key at tape index {@code k}, or -1 after the last. On a
   * distinct-key object that is the next key, with no scan. On another, each later key is checked with
   * {@link #firstScan}, which charges one tick per key it compares (ADR-0008, ADR-0034).
   */
  @Override
  public int next(RunState state, int k) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = Tape.end(tape, index);
    int next = Tape.next(tape, k + 1);
    if (Tape.tag(tape[index]) == Tape.DISTINCT) {
      return next < end ? next : -1;
    }
    for (; next < end; next = Tape.next(tape, next + 1)) {
      int scan = firstScan(tape, bytes, index, next);
      state.tick(Math.abs(scan));
      if (scan > 0) {
        return next;
      }
    }
    return -1;
  }

  /**
   * The last value of the key at tape index {@code k}: on a distinct-key object its own value, with no scan; on
   * another, found by {@link #lastScan}, which charges one tick per later key it compares (ADR-0008, ADR-0034).
   */
  @Override
  public JqValue member(RunState state, int k) {
    long[] tape = doc.live();
    if (Tape.tag(tape[index]) == Tape.DISTINCT) {
      return doc.valueAt(tape, k + 1);
    }
    long scan = lastScan(tape, doc.bytes, k, Tape.end(tape, index));
    state.tick((int) (scan >>> 32));
    return doc.valueAt(tape, (int) scan);
  }

  /**
   * Each key at its first position, decoded, with its last value: on a distinct-key object every key with its own
   * value. Each step after a call checks the document again: the action may have read another document onto this tape
   * (ADR-0016, STALE_VIEW).
   */
  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    boolean distinct = Tape.tag(tape[index]) == Tape.DISTINCT;
    int end = Tape.end(tape, index);
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (distinct || isFirst(tape, bytes, index, k)) {
        int value = distinct ? k + 1 : (int) lastScan(tape, bytes, k, end);
        action.accept(StringView.text(bytes, tape[k]), doc.valueAt(tape, value));
        tape = doc.live();
      }
    }
  }

  /** Whether no key before the key at {@code k} of the object at {@code object} equals it. */
  private static boolean isFirst(long[] tape, byte[] bytes, int object, int k) {
    return firstScan(tape, bytes, object, k) > 0;
  }

  /**
   * {@link #isFirst}'s scan from the first key of the object at {@code object} to the first one equal to the key at
   * {@code k}: the number of keys it compares, negated when that key comes before {@code k}.
   */
  public static int firstScan(long[] tape, byte[] bytes, int object, int k) {
    int first = object + 1;
    int compared = 1;
    while (!sameKey(tape, bytes, first, k)) {
      first = Tape.next(tape, first + 1);
      compared++;
    }
    return first == k ? compared : -compared;
  }

  /**
   * The scan of every key after the key at {@code k} for the last one equal to it; {@code end} is the object's
   * {@link Tape#end}. The keys it compares are in the high half, the tape index of that last key's value in the low.
   */
  public static long lastScan(long[] tape, byte[] bytes, int k, int end) {
    int value = k + 1;
    int compared = 0;
    for (int later = Tape.next(tape, k + 1); later < end; later = Tape.next(tape, later + 1)) {
      compared++;
      if (sameKey(tape, bytes, later, k)) {
        value = later + 1;
      }
    }
    return (long) compared << 32 | value;
  }

  /**
   * Two keys are equal when their decoded bytes are: two tag-5 keys compare their bytes, quotes included, without a
   * copy; any other pair compares decoded arrays.
   */
  private static boolean sameKey(long[] tape, byte[] bytes, int a, int b) {
    long x = tape[a];
    long y = tape[b];
    if (Tape.tag(x) == Tape.STRING && Tape.tag(y) == Tape.STRING) {
      int from = Tape.off(x);
      int other = Tape.off(y);
      return Arrays.equals(bytes, from, from + Tape.span(x), bytes, other, other + Tape.span(y));
    }
    return Arrays.equals(JsonStrings.decode(bytes, x), JsonStrings.decode(bytes, y));
  }

  /**
   * Whether the key {@code entry} spells {@code key}: a tag-5 key over the document's bytes, a tag-6 key over its
   * decoded bytes.
   */
  static boolean spells(byte[] bytes, long entry, String key) {
    if (Tape.tag(entry) == Tape.STRING) {
      return spells(bytes, Tape.off(entry) + 1, Tape.off(entry) + Tape.span(entry) - 1, key);
    }
    byte[] decoded = JsonStrings.decode(bytes, entry);
    return spells(decoded, 0, decoded.length, key);
  }

  /**
   * Whether the UTF-8 bytes {@code bytes[p, end)} spell {@code key}, compared by code point. A lone surrogate is no code
   * point, so a key holding one never matches; encoding {@code key} with a charset instead would turn it into {@code ?}
   * and find the key {@code "?"}.
   */
  static boolean spells(byte[] bytes, int p, int end, String key) {
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
      int length = match(bytes, p, end, codePoint);
      if (length == 0) {
        return false;
      }
      p += length;
    }
    return p == end;
  }

  /** The length of the UTF-8 form of {@code codePoint} if {@code bytes[p, end)} starts with it, otherwise 0. */
  private static int match(byte[] bytes, int p, int end, int codePoint) {
    int length = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    if (end - p < length) {
      return 0;
    }
    // The lead byte: the code point alone below 0x80, otherwise 110, 1110 or 11110 and its top bits.
    int shift = 6 * (length - 1);
    int lead = length == 1 ? codePoint : ((0xF00 >> length) & 0xFF) | (codePoint >> shift);
    if ((bytes[p] & 0xFF) != lead) {
      return 0;
    }
    for (int i = 1; i < length; i++) {
      shift -= 6;
      if ((bytes[p + i] & 0xFF) != (0x80 | ((codePoint >> shift) & 0x3F))) {
        return 0;
      }
    }
    return length;
  }
}
