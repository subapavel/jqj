package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.CanonicalForm;
import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.internal.value.NumberView;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.internal.value.StringView;
import io.github.subapavel.jqj.internal.value.Tape;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The writer (ADR-0015, ADR-0027): prints a value as jq's {@code -c} does, or pretty as jq does by default, into a
 * 64 KiB buffer, which it drains into a {@link Target} whenever it is full and once at the end. It walks containers,
 * a document's and built ones alike, with its own stack, so depth costs no Java frames (#64). One per Workspace; not
 * thread-safe.
 *
 * <p>null, the booleans and tag-5 strings are their bytes. A number is copied when it is CANONICAL (ADR-0012) and is
 * written in its canonical form otherwise (#36); a filter's literal number is its canonical form (#61); a computed
 * number is jq's {@code jvp_dtoa_fmt} layout of its double (#65, ADR-0012); a tag-6 string,
 * a tag-6 key and a built string are re-escaped as jq's {@code jvp_dump_string} (#35). A document's object prints each
 * key at its first position with its last value (ADR-0002); a built object prints its key list (#64). A value inside
 * more than {@link #MAX_DEPTH} containers prints as {@code <skipped: too deep>}, as jq's does (jq-ref 1.8.2, observed:
 * DeepBuiltArrayTest), so the stack never holds more than {@code MAX_DEPTH + 1} containers. A target's exception, or a
 * document found stale after a drain, in the middle of a value leaves what was drained before it written.
 *
 * <p>Pretty (#37) changes only the layout of containers: each entry of a non-empty container starts on a new line,
 * indented by two spaces a level, a key is followed by {@code ": "}, and the closing bracket is on its own line at the
 * container's indent. An empty container is {@code []} or {@code {}}, as in compact output.
 */
public final class Printer implements CanonicalForm.Out {
  /** Where the buffer drains: host code, which may read into the Workspace whose document is being printed. */
  public interface Target {
    void drain(byte[] buffer, int length);
  }

  private static final int CAPACITY = 64 * 1024;
  private static final byte[] NULL = {'n', 'u', 'l', 'l'};
  private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
  private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};

  /** The most containers a printed value may be inside; one inside more is {@link #SKIPPED}. */
  private static final int MAX_DEPTH = 10_000;

  /** What jq prints for a value inside more than {@link #MAX_DEPTH} containers. */
  private static final byte[] SKIPPED = "<skipped: too deep>".getBytes(US_ASCII);

  /** jvp_dump_string's {@code %04x}: lowercase. */
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /** jq's default indent: two spaces a level. */
  private static final int INDENT = 2;

  /** The spaces an indent is copied from, a chunk at a time. */
  private static final byte[] SPACES = " ".repeat(256).getBytes(UTF_8);

  private final byte[] buffer = new byte[CAPACITY];

  /**
   * Where a tag-6 string's escape pass goes before it is re-escaped. It grows to at most {@link #CAPACITY}; a longer
   * string decodes into an array of its own, dropped after it (architect's answer to #35 Open 3: bounded memory).
   */
  private byte[] scratch = new byte[0];

  /** Whether the value being printed is laid out as jq's default pretty output; false for compact. */
  private boolean pretty;

  /**
   * The open containers, innermost last: a document's container as its {@link Document}, a built one as itself. A
   * cleared slot holds null, so a printed value is not kept after its line.
   */
  private Object[] open = new Object[16];

  /**
   * Two slots per open container: a document's tape index and the tape index of its next entry to print; a built
   * one's unused slot and the index of its next entry.
   */
  private int[] cursors = new int[32];

  private int pos;
  private Target target;

  /**
   * The document being printed, checked again after a drain in the middle of a value; null for a built value until
   * one of its entries is a view, then that view's document.
   */
  private Document doc;

  /** Prints {@code v}, pretty or compact, and one LF, then drains everything into {@code target} (ADR-0027). */
  public void line(JqValue v, boolean pretty, Target target) {
    // The document of an earlier line may be closed by now; a built value has none to check.
    doc = null;
    this.pretty = pretty;
    this.target = target;
    // A value that ended in a target's exception or a stale document may have left bytes behind.
    pos = 0;
    try {
      walk(v, null, 0);
    } catch (RuntimeException e) {
      // The walk clears each container it closes; one that ends early leaves its open containers behind.
      Arrays.fill(open, null);
      throw e;
    }
    put('\n');
    drain();
  }

  /**
   * The compact text of the value at tape index {@code index} of {@code doc}, a view's {@code toJson()}.
   *
   * @throws io.github.subapavel.jqj.JqTermination with cause {@code STALE_VIEW} when the document is dropped
   */
  public static String json(Document doc, int index) {
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    Printer printer = new Printer();
    printer.target = (buffer, length) -> text.write(buffer, 0, length);
    printer.walk(null, doc, index);
    printer.drain();
    return text.toString(UTF_8);
  }

  /**
   * The compact text of a built value, its {@code toJson()}.
   *
   * @throws io.github.subapavel.jqj.JqTermination with cause {@code STALE_VIEW} when an entry's document is dropped
   */
  public static String json(JqValue v) {
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    Printer printer = new Printer();
    printer.target = (buffer, length) -> text.write(buffer, 0, length);
    printer.walk(v, null, 0);
    printer.drain();
    return text.toString(UTF_8);
  }

  /**
   * The first {@code n} bytes of {@code v}'s compact text, or all of it when it is shorter: what an error shows of a
   * value (ErrorDump). It prints no more than the first drain, a buffer at most, so a long value costs no more.
   */
  public static byte[] head(JqValue v, int n) {
    byte[][] first = new byte[1][];
    try {
      new Printer()
          .line(
              v,
              false,
              (buffer, length) -> {
                // The last drain ends with the LF; a full one is CAPACITY bytes, longer than any head.
                first[0] = Arrays.copyOf(buffer, Math.min(n, length - 1));
                throw Cut.CUT;
              });
    } catch (Cut _) {
      // The head is in first[0].
    }
    return first[0];
  }

  /** Ends {@link #head}'s print at its first drain; it has no stack trace, so it costs nothing to throw. */
  private static final class Cut extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final Cut CUT = new Cut();

    private Cut() {
      super(null, null, false, false);
    }
  }

  /** The byte length of the scratch this printer keeps between values (#35 Open 3). */
  public int retainedScratch() {
    return scratch.length;
  }

  /**
   * Prints a value: {@code v}, or the entry at tape index {@code at} of {@code from} when that is not null. Each
   * value is printed at the depth of the containers open around it, then the innermost container's next entry, or its
   * close, comes next.
   */
  private void walk(JqValue v, Document from, int at) {
    int depth = 0;
    for (;;) {
      if (depth > MAX_DEPTH) {
        put(SKIPPED);
      } else if (from != null) {
        depth = entry(from, at, depth);
      } else {
        depth = value(v, depth);
      }
      // The value ended: the next entry of the innermost open container, or its close. The innermost container's
      // entries are `depth` levels deep.
      for (;; open[--depth] = null) {
        if (depth == 0) {
          return;
        }
        Object container = open[depth - 1];
        int next = cursors[2 * depth - 1];
        if (container instanceof Document d) {
          long[] tape = d.live();
          int index = cursors[2 * depth - 2];
          int end = Tape.end(tape, index);
          boolean array = Tape.tag(tape[index]) == Tape.ARRAY;
          if (array) {
            if (next < end) {
              separate(next > index + 1, depth);
              cursors[2 * depth - 1] = Tape.next(tape, next);
              from = d;
              at = next;
              break;
            }
          } else {
            byte[] bytes = d.bytes();
            while (next < end && !ObjectView.isFirst(tape, bytes, index, next)) {
              next = Tape.next(tape, next + 1);
            }
            if (next < end) {
              // The first key of an object is always printed, so any later printed key follows a comma.
              separate(next > index + 1, depth);
              string(bytes, tape[next]);
              colon();
              cursors[2 * depth - 1] = Tape.next(tape, next + 1);
              from = d;
              at = ObjectView.lastValueOf(tape, bytes, next, end);
              break;
            }
          }
          // An object with entries always prints its first key, so `end` tells an empty object for both kinds.
          close(end > index + 1, depth, array ? ']' : '}');
        } else if (container instanceof BuiltArray a) {
          if (next < a.length()) {
            separate(next > 0, depth);
            cursors[2 * depth - 1] = next + 1;
            from = null;
            v = a.get(next);
            break;
          }
          close(next > 0, depth, ']');
        } else {
          BuiltObject o = (BuiltObject) container;
          if (next < o.size()) {
            separate(next > 0, depth);
            key(o.key(next));
            colon();
            cursors[2 * depth - 1] = next + 1;
            from = null;
            v = o.value(next);
            break;
          }
          close(next > 0, depth, '}');
        }
      }
    }
  }

  /**
   * Prints {@code v} inside {@code depth} containers: a scalar whole, a container's opening bracket. Returns the depth
   * of its entries when it opened a container, otherwise {@code depth}.
   */
  private int value(JqValue v, int depth) {
    switch (v) {
      case JqNull _ -> put(NULL);
      case JqBool b -> put(b.value() ? TRUE : FALSE);
      case LiteralNumber n -> put(n.canonical());
      case DoubleNumber n -> put(n.toJson().getBytes(US_ASCII));
      case BuiltString s -> built(s);
      case NumberView n -> depth = entry(n.doc(), n.index(), depth);
      case StringView s -> depth = entry(s.doc(), s.index(), depth);
      case ArrayView a -> depth = entry(a.doc(), a.index(), depth);
      case ObjectView o -> depth = entry(o.doc(), o.index(), depth);
      case BuiltArray a -> {
        put('[');
        depth = push(a, 0, 0, depth);
      }
      case BuiltObject o -> {
        put('{');
        depth = push(o, 0, 0, depth);
      }
    }
    return depth;
  }

  /**
   * Prints the entry at tape index {@code at} of {@code d}, after checking that the document is live (ADR-0026), as
   * {@link #value} prints a value.
   */
  private int entry(Document d, int at, int depth) {
    long[] tape = d.live();
    doc = d;
    long entry = tape[at];
    int tag = Tape.tag(entry);
    if (tag == Tape.ARRAY || tag == Tape.OBJECT) {
      put(tag == Tape.ARRAY ? '[' : '{');
      return push(d, at, at + 1, depth);
    }
    scalar(d.bytes(), entry);
    return depth;
  }

  /**
   * Opens {@code container} inside {@code depth} others, with its two {@link #cursors}: a document's tape index of the
   * container and of its first entry, or a built container's 0 and 0. Returns the depth of its entries.
   */
  private int push(Object container, int index, int first, int depth) {
    if (depth == open.length) {
      open = Arrays.copyOf(open, depth << 1);
      cursors = Arrays.copyOf(cursors, depth << 2);
    }
    open[depth] = container;
    cursors[2 * depth] = index;
    cursors[2 * depth + 1] = first;
    return depth + 1;
  }

  /** Before an entry {@code depth} levels deep: a comma unless it is the first, then pretty its new line. */
  private void separate(boolean comma, int depth) {
    if (comma) {
      put(',');
    }
    newline(depth);
  }

  /** After a key: a colon, and pretty a space. */
  private void colon() {
    put(':');
    if (pretty) {
      put(' ');
    }
  }

  /** A built object's key: a built string, or a document's string, its document checked first (ADR-0026). */
  private void key(JqString k) {
    switch (k) {
      case BuiltString s -> built(s);
      case StringView s -> {
        long[] tape = s.doc().live();
        doc = s.doc();
        string(s.doc().bytes(), tape[s.index()]);
      }
    }
  }

  /** A scalar entry: null, a boolean and a tag-5 string are their bytes, a CANONICAL number too. */
  private void scalar(byte[] bytes, long entry) {
    int span = Tape.span(entry);
    switch (Tape.tag(entry)) {
      case Tape.NUMBER -> {
        if ((span & Tape.CANONICAL) != 0) {
          put(bytes, Tape.off(entry), span & Tape.NUMBER_LENGTH);
        } else {
          CanonicalForm.write(bytes, Tape.off(entry), span & Tape.NUMBER_LENGTH, this);
        }
      }
      case Tape.STRING_REENCODE -> string(bytes, entry);
      // Tape.NULL, FALSE, TRUE and STRING: a scalar's span is its byte length (ADR-0002).
      default -> put(bytes, Tape.off(entry), span);
    }
  }

  /**
   * A string entry, a key included: tag 5 is its bytes, quotes included, so an untouched string stays byte-identical;
   * tag 6 runs the escape pass into {@link #scratch}, or into an array of its own when longer than it may grow, then
   * prints as {@link #quoted}.
   */
  private void string(byte[] bytes, long entry) {
    int off = Tape.off(entry);
    int span = Tape.span(entry);
    if (Tape.tag(entry) == Tape.STRING) {
      put(bytes, off, span);
      return;
    }
    int length = span - 2;
    byte[] s;
    if (length > CAPACITY) {
      s = new byte[length];
    } else {
      if (length > scratch.length) {
        scratch = new byte[length];
      }
      s = scratch;
    }
    quoted(s, 0, JsonStrings.unescape(bytes, off + 1, off + span - 1, s));
  }

  /** A built string: well-formed UTF-8, printed as {@link #quoted}. */
  private void built(BuiltString s) {
    quoted(s.bytes(), 0, s.byteLength());
  }

  /**
   * jq's {@code jvp_dump_string} with {@code ascii_only} off, over {@code s[from, to)}: {@code "} and {@code \\} after a
   * backslash, BS, TAB, LF, FF and CR as {@code \\b \\t \\n \\f \\r}, any other byte below 0x20 and DEL as lowercase
   * {@code \\u%04x}; a sequence {@link JsonStrings#step} calls invalid as U+FFFD; every other byte as it is, {@code /}
   * included. Runs of bytes that need nothing are copied at once.
   */
  private void quoted(byte[] s, int from, int to) {
    put('"');
    int run = from;
    int i = from;
    while (i < to) {
      int c = s[i] & 0xFF;
      if (c < 0x80) {
        if (c >= 0x20 && c != '"' && c != '\\' && c != 0x7F) {
          i++;
          continue;
        }
        put(s, run, i - run);
        escapeSequence(c);
        i++;
      } else {
        int length = JsonStrings.step(s, i, to);
        if (length > 0) {
          i += length;
          continue;
        }
        put(s, run, i - run);
        put(JsonStrings.REPLACEMENT);
        i -= length;
      }
      run = i;
    }
    put(s, run, i - run);
    put('"');
  }

  /** The escape sequence of an ASCII byte that {@link #quoted} does not copy. */
  private void escapeSequence(int c) {
    put('\\');
    switch (c) {
      case '"', '\\' -> put((char) c);
      case '\b' -> put('b');
      case '\t' -> put('t');
      case '\n' -> put('n');
      case '\f' -> put('f');
      case '\r' -> put('r');
      // Any other byte below 0x20, and DEL.
      default -> {
        put('u');
        put('0');
        put('0');
        put(HEX[c >> 4]);
        put(HEX[c & 0xF]);
      }
    }
  }

  /**
   * Closes a container whose entries are {@code depth} levels deep. Pretty, a non-empty container's bracket goes on a
   * new line at the container's own indent; an empty one, and every container in compact output, closes right after
   * its opening bracket or last entry.
   */
  private void close(boolean entries, int depth, char bracket) {
    if (entries) {
      newline(depth - 1);
    }
    put(bracket);
  }

  /** Pretty only: an LF and {@code levels} indents. Compact output has neither. */
  private void newline(int levels) {
    if (!pretty) {
      return;
    }
    put('\n');
    for (int n = levels * INDENT; n > 0; n -= SPACES.length) {
      put(SPACES, 0, Math.min(n, SPACES.length));
    }
  }

  private void put(byte[] literal) {
    put(literal, 0, literal.length);
  }

  /** Appends {@code src[off, off + length)}, filling the buffer and draining it as often as it is full. */
  @Override
  public void put(byte[] src, int off, int length) {
    int room = CAPACITY - pos;
    while (length > room) {
      System.arraycopy(src, off, buffer, pos, room);
      pos = CAPACITY;
      off += room;
      length -= room;
      drainFull();
      room = CAPACITY;
    }
    System.arraycopy(src, off, buffer, pos, length);
    pos += length;
  }

  @Override
  public void put(char c) {
    if (pos == CAPACITY) {
      drainFull();
    }
    buffer[pos++] = (byte) c;
  }

  /**
   * Drains a full buffer in the middle of a value, then checks the document again when the value has one: the target
   * is host code and may have read into the same Workspace (ADR-0027). Only a document's value, a built string, a
   * built container or a filter's literal number fills the buffer: null and a boolean alone are at most five bytes and
   * start in an empty one. A built string and a literal number have no document; a built container has its views'.
   */
  private void drainFull() {
    drain();
    if (doc != null) {
      doc.live();
    }
  }

  private void drain() {
    target.drain(buffer, pos);
    pos = 0;
  }
}
