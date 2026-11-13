package io.github.subapavel.jqj.internal.json;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.subapavel.jqj.JqSyntaxException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.internal.value.Tape;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * The structural reader (ADR-0015): exactly one strict JSON document per read, written onto a tape that is reused
 * from read to read (ADR-0002). The document it returned last goes stale as the next read starts, a failed read
 * included (ADR-0026). One per Workspace; not thread-safe.
 *
 * <p>Until spec 5, a lenient token and a second top-level value are malformed, and a malformed document ends in the
 * Stub with the line and column of its detecting byte (#22, ADR-0024). A limit is jq's own error. Numbers get
 * ADR-0012's FITS_LONG and CANONICAL flags; strings are classified by ADR-0015's tag 5 and tag 6.
 *
 * <p>At an object's close the reader tries to prove its keys distinct (ADR-0002 as amended by F-40): an object of more
 * than 8 keys, all of them tag 5, none repeated, is tag 10; every other object is tag 9. The proof puts each key into a
 * reused hash table, probing linearly from its home slot, and gives up (tag 9) at a key that needs more than 8 probes,
 * so hostile keys cost at most 8 compares each.
 */
public final class JsonReader {
  /** jq's parse depth (ADR-0015, ADR-0026, F-22), a constant until spec 2 wires {@code Limits.nestingDepth}. */
  private static final int DEPTH = 10_000;

  /** What {@link #peek} gives at the end of the bytes. */
  private static final int EOF = -1;

  /** The magnitude of {@link Long#MIN_VALUE}, the largest 19-digit integer a long holds (negated). */
  private static final String LONG_MIN_MAGNITUDE = "9223372036854775808";

  /** Only an object of more keys than this is proved distinct (ADR-0002 as amended by F-40, O2 of #82's approval). */
  private static final int PROVED = 8;

  /** The slots a key may probe, its home slot first; a key that needs more ends the proof (ADR-0002 as amended). */
  private static final int PROBES = 8;

  /**
   * The proof's table slots per key, rounded up to a power of two: with 4, one object of 78 841 random keys in nine
   * needed more than {@link #PROBES} probes somewhere, with 8 none of 200 did (bench/RESULTS.md, "#82").
   */
  private static final int SLOTS_PER_KEY = 8;

  /** The proof's largest table: an object of more than 2^17 keys has a load above 1/8. */
  private static final int MAX_SLOTS = 1 << 20;

  private final TapeLimits limits;

  /** Reused by every read; it doubles when full and never shrinks. */
  private long[] tape = new long[256];

  /**
   * jq's parser stack (F-22): the tape index of each open container, and above an object, while one of its keys has
   * its value open, the number of keys read so far, negated. A container's slot is never negative.
   */
  private int[] stack = new int[16];

  /**
   * The proof's hash table, reused by every object: a slot holds the tape index of a key, or 0 for none, since a key
   * is never the root. It is empty between proofs, and grows to the largest table a proof has needed.
   */
  private int[] slots = new int[0];

  private Document last;

  /** The index of the segment's first byte in the array being read: an error's offset counts from it. */
  private int origin;

  public JsonReader(TapeLimits limits) {
    this.limits = limits;
  }

  /**
   * Reads one document. A writable heap segment over a {@code byte[]} is read in place; any other segment is copied
   * once (ADR-0015, ADR-0016). One leading UTF-8 BOM is skipped; an error's column counts from after it, its offset
   * from the segment's first byte.
   *
   * @throws JqSyntaxException when the bytes are not exactly one JSON document, or a limit is exceeded
   */
  public Document read(MemorySegment json) {
    if (last != null) {
      last.drop();
    }
    long size = json.byteSize();
    if (size > limits.document()) {
      throw overLimit("document size", json, 0, 0, size, limits.document());
    }
    byte[] bytes;
    // heapBase() is empty for a read-only segment (F-12), so only a writable one is read in place.
    if (json.heapBase().orElse(null) instanceof byte[] array) {
      bytes = array;
      origin = (int) json.address();
    } else {
      bytes = json.toArray(JAVA_BYTE);
      origin = 0;
    }
    int end = origin + (int) size;
    parse(bytes, hasBom(bytes, origin, end) ? origin + 3 : origin, end);
    last = new Document(bytes, tape);
    return last;
  }

  /** Whether {@code b[p, end)} starts with the UTF-8 BOM, EF BB BF. */
  private static boolean hasBom(byte[] b, int p, int end) {
    return end - p >= 3 && b[p] == (byte) 0xEF && b[p + 1] == (byte) 0xBB && b[p + 2] == (byte) 0xBF;
  }

  /** Writes the value in {@code b[base, end)} onto the tape, its root at index 0. */
  private void parse(byte[] b, int base, int end) {
    int t = 0;
    int sp = 0;
    int p = skip(b, base, end);
    value:
    for (;;) {
      // p is where a value must start.
      int c = peek(b, p, end);
      if (c == '[' || c == '{') {
        // F-22: jq rejects a '[' or '{' when its parser stack already holds DEPTH slots.
        if (sp >= DEPTH) {
          throw overLimit("depth", b, base, end, p);
        }
        int kind = c == '[' ? Tape.ARRAY : Tape.OBJECT;
        int close = kind == Tape.ARRAY ? ']' : '}';
        p = skip(b, p + 1, end);
        if (peek(b, p, end) != close) {
          sp = push(sp, t);
          // The span and the closing bracket's offset are written when it closes.
          put(t++, Tape.entry(kind, 0, 0));
          if (kind == Tape.OBJECT) {
            // Its first key.
            sp = push(sp, -1);
            p = key(b, base, end, p, t++);
          }
          continue;
        }
        put(t++, Tape.entry(kind, 0, p));
        p++;
      } else if (c == '"') {
        p = string(b, base, end, p, t++);
      } else if (c == '-' || isDigit(c)) {
        p = number(b, base, end, p, t++);
      } else if (c == 't') {
        put(t++, Tape.entry(Tape.TRUE, 4, p));
        p = literal(b, base, end, p, "true");
      } else if (c == 'f') {
        put(t++, Tape.entry(Tape.FALSE, 5, p));
        p = literal(b, base, end, p, "false");
      } else if (c == 'n') {
        put(t++, Tape.entry(Tape.NULL, 4, p));
        p = literal(b, base, end, p, "null");
      } else {
        throw malformed(b, base, end, p);
      }
      // A value ended: close the containers it ends, then a separator leads to the next value. At depth 0 only
      // whitespace may follow.
      for (;;) {
        p = skip(b, p, end);
        if (sp == 0) {
          if (p != end) {
            throw malformed(b, base, end, p);
          }
          return;
        }
        // The top slot is an object's keys so far, negated, when one of its members' value ended, otherwise the index
        // of an open array: an object never reaches this loop before its first key, and an empty one is closed above.
        int top = stack[sp - 1];
        boolean member = top < 0;
        c = peek(b, p, end);
        if (c == ',') {
          p = skip(b, p + 1, end);
          if (member) {
            stack[sp - 1] = top - 1;
            p = key(b, base, end, p, t++);
          }
          continue value;
        }
        if (c != (member ? '}' : ']')) {
          throw malformed(b, base, end, p);
        }
        if (member) {
          sp--;
        }
        int open = stack[--sp];
        int span = t - open - 1;
        if (span > limits.container()) {
          throw overLimit("container size", b, base, end, p);
        }
        // An array's top slot is its tape index, never negative, so only an object of more than PROVED keys is tried.
        int tag = -top > PROVED && distinct(b, open, t, -top) ? Tape.DISTINCT : Tape.tag(tape[open]);
        tape[open] = Tape.entry(tag, span, p);
        p++;
      }
    }
  }

  /** Pushes a slot onto the parser stack, doubling it when full; returns the new depth. */
  private int push(int sp, int slot) {
    if (sp == stack.length) {
      stack = Arrays.copyOf(stack, sp << 1);
    }
    stack[sp] = slot;
    return sp + 1;
  }

  /**
   * Whether the {@code keys} keys of the object at tape index {@code object}, whose entries end before {@code end}, are
   * all tag 5 and none repeats (ADR-0002 as amended by F-40). The table has 8 slots per key, rounded up to a power of
   * two, at most {@link #MAX_SLOTS}; it grows when that is more than it has, and is empty again afterwards.
   */
  private boolean distinct(byte[] b, int object, int end, int keys) {
    int capacity = Math.min(MAX_SLOTS, Integer.highestOneBit(keys * SLOTS_PER_KEY - 1) << 1);
    if (slots.length < capacity) {
      slots = new int[capacity];
    }
    boolean distinct = insertAll(b, object, end, capacity - 1);
    Arrays.fill(slots, 0, capacity, 0);
    return distinct;
  }

  /**
   * Puts each key of the object at tape index {@code object} into the table of {@code mask + 1} slots, in source order;
   * false at the first key that is not tag 5 or not {@link #insert}ed.
   */
  private boolean insertAll(byte[] b, int object, int end, int mask) {
    for (int k = object + 1; k < end; k = Tape.next(tape, k + 1)) {
      if (Tape.tag(tape[k]) != Tape.STRING || !insert(b, k, mask)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Puts the tag-5 key at tape index {@code k} into the first free slot from its home slot, {@code keyHash & mask};
   * false when a slot on the way holds a key of the same bytes, a repeat, or when {@link #PROBES} slots are all taken.
   */
  private boolean insert(byte[] b, int k, int mask) {
    long key = tape[k];
    int slot = keyHash(b, key);
    for (int probe = 0; probe < PROBES; probe++, slot++) {
      int other = slots[slot & mask];
      if (other == 0) {
        slots[slot & mask] = k;
        return true;
      }
      long held = tape[other];
      int from = Tape.off(key);
      int at = Tape.off(held);
      if (Arrays.equals(b, from, from + Tape.span(key), b, at, at + Tape.span(held))) {
        return false;
      }
    }
    return false;
  }

  /**
   * The proof's hash of the tag-5 key {@code entry} over {@code bytes}: a function of its bytes alone, quotes included,
   * wherever they lie. MurmurHash3's finaliser (public domain) spreads the polynomial's high bits into the low ones,
   * which make the home slot.
   */
  static int keyHash(byte[] bytes, long entry) {
    int from = Tape.off(entry);
    int to = from + Tape.span(entry);
    int h = 0;
    for (int i = from; i < to; i++) {
      h = 31 * h + bytes[i];
    }
    h = (h ^ (h >>> 16)) * 0x85EB_CA6B;
    h = (h ^ (h >>> 13)) * 0xC2B2_AE35;
    return h ^ (h >>> 16);
  }

  /** Reads the key at p into tape index t, then its colon; returns where the key's value must start. */
  private int key(byte[] b, int base, int end, int p, int t) {
    if (peek(b, p, end) != '"') {
      throw malformed(b, base, end, p);
    }
    p = skip(b, string(b, base, end, p, t), end);
    if (peek(b, p, end) != ':') {
      throw malformed(b, base, end, p);
    }
    return skip(b, p + 1, end);
  }

  /** Reads the string whose opening quote is at p into tape index t; returns the position after its closing quote. */
  private int string(byte[] b, int base, int end, int p, int t) {
    int start = p++;
    int tag = Tape.STRING;
    boolean unpaired = false;
    for (int c = peek(b, p, end); c != '"'; c = peek(b, p, end)) {
      if (c == '\\') {
        tag = Tape.STRING_REENCODE;
        int next = escapeSequence(b, base, end, p + 1);
        if (isSurrogate(b, p, end, 0xD800) && !isSurrogate(b, next, end, 0xDC00)) {
          unpaired = true;
        }
        p = next;
      } else if (c < 0x20) {
        // A raw byte below 0x20 must be escaped (ADR-0015); EOF leaves the string unfinished.
        throw malformed(b, base, end, p);
      } else if (c < 0x7F) {
        p++;
      } else {
        // DEL and bytes that are not well-formed UTF-8 make tag 6 (ADR-0015); a bad byte is passed one at a time, so
        // a quote, a backslash or a control byte after it is still seen.
        int length = c == 0x7F ? 0 : sequence(b, p, end);
        if (length == 0) {
          tag = Tape.STRING_REENCODE;
          p++;
        } else {
          p += length;
        }
      }
    }
    if (unpaired) {
      // ADR-0015: a high surrogate escape not followed by a low one; jq's found_string meets it at the closing quote.
      throw malformed(b, base, end, p);
    }
    int span = p + 1 - start;
    if (span > limits.string()) {
      throw overLimit("string size", b, base, end, p);
    }
    put(t, Tape.entry(tag, span, start));
    return p + 1;
  }

  /**
   * The length of the well-formed UTF-8 sequence whose lead byte, from 0x80, is at p, or 0 when the bytes there are
   * not one (the Unicode standard's table 3-7): no overlong form, no surrogate, nothing above U+10FFFF.
   */
  private static int sequence(byte[] b, int p, int end) {
    int c = b[p] & 0xFF;
    int length;
    int low = 0x80;
    int high = 0xBF;
    if (c < 0xC2) {
      // A continuation byte, or the lead of an overlong two-byte form.
      return 0;
    } else if (c < 0xE0) {
      length = 2;
    } else if (c < 0xF0) {
      length = 3;
      if (c == 0xE0) {
        low = 0xA0;
      } else if (c == 0xED) {
        high = 0x9F;
      }
    } else if (c < 0xF5) {
      length = 4;
      if (c == 0xF0) {
        low = 0x90;
      } else if (c == 0xF4) {
        high = 0x8F;
      }
    } else {
      return 0;
    }
    int second = peek(b, p + 1, end);
    if (second < low || second > high) {
      return 0;
    }
    for (int i = 2; i < length; i++) {
      // EOF is -1, below every continuation byte.
      int next = peek(b, p + i, end);
      if (next < 0x80 || next > 0xBF) {
        return 0;
      }
    }
    return length;
  }

  /**
   * Whether {@code b[p, end)} starts with a {@code \\u} escape whose code unit is in {@code [low, low + 0x400)}: from
   * D800 a high surrogate, from DC00 a low one. A digit that is not hex (or EOF) is -1, which sets every bit above it,
   * so the unit's top six bits are all ones and match neither; the reader then stops at that digit anyway.
   */
  private static boolean isSurrogate(byte[] b, int p, int end, int low) {
    if (peek(b, p, end) != '\\' || peek(b, p + 1, end) != 'u') {
      return false;
    }
    int unit = 0;
    for (int i = p + 2; i < p + 6; i++) {
      unit = unit << 4 | Character.digit(peek(b, i, end), 16);
    }
    return (unit & 0xFC00) == low;
  }

  /** Checks the escape sequence whose letter is at p: one of {@code "\/bfnrt}, or {@code u} and four hex digits. */
  private int escapeSequence(byte[] b, int base, int end, int p) {
    return switch (peek(b, p, end)) {
      case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> p + 1;
      case 'u' -> {
        for (int i = 1; i < 5; i++) {
          if (Character.digit(peek(b, p + i, end), 16) < 0) {
            throw malformed(b, base, end, p + i);
          }
        }
        yield p + 5;
      }
      default -> throw malformed(b, base, end, p);
    };
  }

  /**
   * Reads the number at p into tape index t: strict JSON, {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}
   * (ADR-0015); returns the position after it.
   */
  private int number(byte[] b, int base, int end, int p, int t) {
    int start = p;
    boolean negative = b[p] == '-';
    if (negative) {
      p++;
    }
    int integer = p;
    p = peek(b, p, end) == '0' ? p + 1 : digits(b, base, end, p);
    int integerEnd = p;
    int flags = Tape.CANONICAL;
    if (peek(b, p, end) == '.') {
      int fraction = p + 1;
      p = digits(b, base, end, fraction);
      if (b[integer] == '0' && !canonicalFraction(b, fraction, p)) {
        flags = 0;
      }
    }
    int c = peek(b, p, end);
    if (c == 'e' || c == 'E') {
      // ADR-0012: jq prints a token with an exponent in another text.
      flags = 0;
      c = peek(b, ++p, end);
      if (c == '+' || c == '-') {
        p++;
      }
      p = digits(b, base, end, p);
    }
    int length = p - start;
    if (length > limits.number()) {
      throw overLimit("number size", b, base, end, p);
    }
    if (p == integerEnd && fitsLong(b, negative, integer, p)) {
      flags |= Tape.FITS_LONG;
    }
    put(t, Tape.entry(Tape.NUMBER, length | flags, start));
    return p;
  }

  /**
   * ADR-0012 CANONICAL for the fraction digits {@code b[from, to)} of a {@code [-]0.} token: fewer than 6 leading
   * zeros, or fewer than 7 when the fraction is all zeros. jq prints the others in exponent form.
   */
  private static boolean canonicalFraction(byte[] b, int from, int to) {
    int z = from;
    while (z < to && b[z] == '0') {
      z++;
    }
    return z - from < (z == to ? 7 : 6);
  }

  /** Reads one or more digits from p; returns the position after the last. */
  private int digits(byte[] b, int base, int end, int p) {
    if (!isDigit(peek(b, p, end))) {
      throw malformed(b, base, end, p);
    }
    do {
      p++;
    } while (isDigit(peek(b, p, end)));
    return p;
  }

  /** ADR-0012 FITS_LONG for the integer digits {@code b[from, to)} of a token: a long, except {@code -0}. */
  private static boolean fitsLong(byte[] b, boolean negative, int from, int to) {
    int digits = to - from;
    if (digits < 19) {
      return !negative || b[from] != '0';
    }
    if (digits > 19) {
      return false;
    }
    for (int i = 0; i < 19; i++) {
      int digit = b[from + i];
      int limit = LONG_MIN_MAGNITUDE.charAt(i);
      if (digit < limit) {
        return true;
      }
      if (digit > limit) {
        return false;
      }
    }
    // Exactly the magnitude of Long.MIN_VALUE.
    return negative;
  }

  /** Checks the bytes of {@code word} after its first, which is at p; returns the position after it. */
  private int literal(byte[] b, int base, int end, int p, String word) {
    for (int i = 1; i < word.length(); i++) {
      if (peek(b, p + i, end) != word.charAt(i)) {
        throw malformed(b, base, end, p + i);
      }
    }
    return p + word.length();
  }

  /** The first position from p whose byte is not SP, TAB, LF or CR (ADR-0015), or end. */
  private static int skip(byte[] b, int p, int end) {
    for (int c = peek(b, p, end); c == ' ' || c == '\n' || c == '\r' || c == '\t'; c = peek(b, p, end)) {
      p++;
    }
    return p;
  }

  /** The unsigned byte at p, or {@link #EOF} at the end. */
  private static int peek(byte[] b, int p, int end) {
    return p < end ? b[p] & 0xFF : EOF;
  }

  private static boolean isDigit(int c) {
    return c >= '0' && c <= '9';
  }

  /** Writes the entry at tape index t, doubling the tape when it is full. */
  private void put(int t, long entry) {
    if (t == tape.length) {
      // B bytes hold at most (B + 1) / 2 entries, fewer than 2^30 here, so doubling from 256 never passes 2^30.
      tape = Arrays.copyOf(tape, t << 1);
    }
    tape[t] = entry;
  }

  private JqSyntaxException malformed(byte[] b, int base, int end, int d) {
    Position at = Position.of(MemorySegment.ofArray(b), origin, base, end, d);
    return Stub.malformed(at.offset(), at.line(), at.column());
  }

  private JqSyntaxException overLimit(String limit, byte[] b, int base, int end, int d) {
    return overLimit(limit, MemorySegment.ofArray(b), origin, base, end, d);
  }

  /** ADR-0015's text, without the Stub mark: jq has no such limit, so no replay will make it. */
  private static JqSyntaxException overLimit(String limit, MemorySegment in, long origin, long base, long end, long d) {
    Position at = Position.of(in, origin, base, end, d);
    return new JqSyntaxException(
        "Exceeds " + limit + " limit for parsing at line " + at.line() + ", column " + at.column(), at.offset(), at.line(), at.column());
  }

  /**
   * Where the detecting byte d points (#32 design, §1): k is d + 1, or end at EOF; the line is 1 plus the LFs in
   * {@code [base, k)}, the column k - 1 minus the last of them, or k - base without one; the offset is d - origin.
   * The base is after a skipped BOM, the origin the segment's first byte (#33 design, section 1).
   */
  private record Position(long offset, int line, int column) {
    static Position of(MemorySegment in, long origin, long base, long end, long d) {
      long k = Math.min(d + 1, end);
      int line = 1;
      long lastLf = base - 1;
      for (long i = base; i < k; i++) {
        if (in.get(JAVA_BYTE, i) == '\n') {
          line++;
          lastLf = i;
        }
      }
      return new Position(d - origin, line, (int) (k - 1 - lastLf));
    }
  }
}
