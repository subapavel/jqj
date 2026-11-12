package io.github.subapavel.jqj.internal.op;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.subapavel.jqj.internal.json.ErrorDump;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;

/**
 * jq 1.8.2's {@code .[a:b]}, which is {@code jv_get} with the key {@code {"start": a, "end": b}} (jq-ref, recorded:
 * diff/slice.rec): on null, null; on an array its elements {@code [a, b)} as a {@link BuiltArray}, on a string its code
 * points {@code [a, b)}, each bound clamped as jq's {@code parse_slice} does; on any other kind jq's error, which shows
 * the key. A bound that is neither null nor a number is jq's error on an array or a string. The quiet variant
 * {@code .[a:b]?} drops either error (ADR-0034). A missing bound is a Const null.
 */
public final class Slice implements Fn2 {
  /** {@code .[a:b]}. */
  public static final Operation PLAIN = new Operation(".[a:b]", new Slice(false));

  /** {@code .[a:b]?}. */
  public static final Operation QUIET = new Operation(".[a:b]?", new Slice(true));

  /** What a bound's dump keeps at most: one byte more than {@link ErrorDump} shows whole. */
  private static final int HEAD = 30;

  /** Whether this is {@code .[a:b]?}, which drops its own error. */
  private final boolean quiet;

  private Slice(boolean quiet) {
    this.quiet = quiet;
  }

  @Override
  public Object apply(RunState state, Object in, Object a, Object b) {
    JqValue t = (JqValue) in;
    if (t instanceof JqNull) {
      return t;
    }
    if (t instanceof JqArray || t instanceof JqString) {
      JqValue sliced = of(state, t, (JqValue) a, (JqValue) b);
      if (sliced != null) {
        return sliced;
      }
      if (quiet) {
        return CallForm.NONE;
      }
      throw Errors.sliceIndices();
    }
    if (quiet) {
      return CallForm.NONE;
    }
    throw Errors.cannotIndex(t, "object", shown((JqValue) a, (JqValue) b));
  }

  /** What jq's error shows of the key {@code {"start": from, "end": to}}. */
  private static String shown(JqValue from, JqValue to) {
    ByteArrayOutputStream key = new ByteArrayOutputStream();
    key.writeBytes(new byte[] {'{', '"', 's', 't', 'a', 'r', 't', '"', ':'});
    key.writeBytes(Printer.head(from, HEAD));
    key.writeBytes(new byte[] {',', '"', 'e', 'n', 'd', '"', ':'});
    key.writeBytes(Printer.head(to, HEAD));
    key.write('}');
    return ErrorDump.of(key.toByteArray(), '}');
  }

  /**
   * The slice {@code [from, to)} of {@code t}, an array or a string, or Java null when a bound is neither null nor a
   * number; a Java null bound, a key {@link Index} found missing, is neither.
   */
  static JqValue of(RunState state, JqValue t, JqValue from, JqValue to) {
    if (!(from instanceof JqNull || from instanceof JqNumber) || !(to instanceof JqNull || to instanceof JqNumber)) {
      return null;
    }
    if (t instanceof JqString s) {
      return string(state, s, from, to);
    }
    return switch ((JqArray) t) {
      case ArrayView view -> {
        long range = bounds(view.length(state), from, to);
        yield view.slice(state, (int) (range >>> 32), (int) range);
      }
      case BuiltArray built -> {
        long range = bounds(built.length(), from, to);
        yield built.slice(state, (int) (range >>> 32), (int) range);
      }
    };
  }

  /** The length of {@code array}: a view walks its tape and charges a tick per element (ADR-0034). */
  static int length(RunState state, JqArray array) {
    return switch (array) {
      case ArrayView view -> view.length(state);
      case BuiltArray built -> built.length();
    };
  }

  /**
   * The code points {@code [from, to)} of {@code s}, well-formed UTF-8 (ADR-0016), where a code point starts at each
   * byte that is no continuation byte. Counting them is a loop as long as the string, so it charges one tick per
   * started KiB, the writer's rate (TextFormat).
   */
  private static JqValue string(RunState state, JqString s, JqValue from, JqValue to) {
    MemorySegment utf8 = s.utf8();
    long bytes = utf8.byteSize();
    state.tick((int) ((bytes + 1023) >>> 10));
    int points = 0;
    for (long i = 0; i < bytes; i++) {
      if (starts(utf8, i)) {
        points++;
      }
    }
    long range = bounds(points, from, to);
    long first = offset(utf8, 0, (int) (range >>> 32));
    long end = offset(utf8, first, (int) range - (int) (range >>> 32));
    return BuiltString.of(utf8.asSlice(first, end - first).toArray(JAVA_BYTE));
  }

  /** Whether the byte at {@code i} starts a code point. */
  private static boolean starts(MemorySegment utf8, long i) {
    return (utf8.get(JAVA_BYTE, i) & 0xC0) != 0x80;
  }

  /** The byte offset {@code n} code points after the code point that starts at {@code from}. */
  private static long offset(MemorySegment utf8, long from, int n) {
    long at = from;
    long bytes = utf8.byteSize();
    for (int k = 0; k < n; k++) {
      do {
        at++;
      } while (at < bytes && !starts(utf8, at));
    }
    return at;
  }

  /**
   * jq's {@code parse_slice} over {@code length} elements: the start, then the end, each null or a number, as {@code
   * start << 32 | end} with {@code 0 <= start <= end <= length}. The start is 0 for null or NaN, counts from the end
   * when negative, and is clamped to {@code [0, length]}, then cut toward zero. The end is the length for null or NaN,
   * counts from the end when negative, is the start when still negative, is clamped to the length, and rounds up a
   * fraction below it; an end before the start is the start.
   */
  static long bounds(int length, JqValue from, JqValue to) {
    double start = from instanceof JqNumber n ? n.doubleValue() : 0;
    if (Double.isNaN(start)) {
      start = 0;
    }
    if (start < 0) {
      start += length;
    }
    if (start < 0) {
      start = 0;
    }
    if (start > length) {
      start = length;
    }
    int s = (int) start;
    double end = to instanceof JqNumber n ? n.doubleValue() : length;
    if (Double.isNaN(end)) {
      end = length;
    }
    if (end < 0) {
      end += length;
    }
    if (end < 0) {
      end = s;
    }
    // The cast saturates, so an end past the int range is past the length.
    int e = (int) end;
    if (e > length) {
      e = length;
    }
    if (e < length && e < end) {
      e++;
    }
    if (e < s) {
      e = s;
    }
    return (long) s << 32 | e;
  }
}
