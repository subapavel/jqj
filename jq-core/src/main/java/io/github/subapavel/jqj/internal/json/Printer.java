package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.CanonicalForm;
import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.internal.value.NumberView;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.internal.value.StringView;
import io.github.subapavel.jqj.internal.value.Tape;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The writer (ADR-0015, ADR-0027): prints a value as jq's {@code -c} does, or pretty as jq does by default, into a
 * 64 KiB buffer, which it drains into a {@link Target} whenever it is full and once at the end. It walks a container
 * with its own stack, so depth costs no Java frames. One per Workspace; not thread-safe.
 *
 * <p>null, the booleans and tag-5 strings are their bytes. A number is copied when it is CANONICAL (ADR-0012) and is
 * written in its canonical form otherwise (#36); a filter's literal number is its canonical form (#61); a tag-6 string, a tag-6 key and a built string are re-escaped as
 * jq's {@code jvp_dump_string} (#35). An object prints each key at its first position with its
 * last value (ADR-0002). A target's exception, or a document found stale after a drain, in the middle of a value
 * leaves what was drained before it written.
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

  /** Two slots per open container: its tape index, then the index of its next entry to print. */
  private int[] stack = new int[32];

  private int pos;
  private Target target;

  /** The document being printed, checked again after a drain in the middle of a value; null for a built value. */
  private Document doc;

  /** Prints {@code v}, pretty or compact, and one LF, then drains everything into {@code target} (ADR-0027). */
  public void line(JqValue v, boolean pretty, Target target) {
    // The document of an earlier line may be closed by now; a built value has none to check.
    doc = null;
    this.pretty = pretty;
    this.target = target;
    // A value that ended in a target's exception or a stale document may have left bytes behind.
    pos = 0;
    switch (v) {
      case JqNull _ -> put(NULL);
      case JqBool b -> put(b.value() ? TRUE : FALSE);
      case NumberView n -> value(n.doc(), n.index());
      case LiteralNumber n -> put(n.canonical());
      case StringView s -> value(s.doc(), s.index());
      case BuiltString s -> built(s);
      case ArrayView a -> value(a.doc(), a.index());
      case ObjectView o -> value(o.doc(), o.index());
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
    printer.value(doc, index);
    printer.drain();
    return text.toString(UTF_8);
  }

  /** The compact text of a built string, its {@code toJson()}. */
  public static String json(BuiltString s) {
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    Printer printer = new Printer();
    printer.target = (buffer, length) -> text.write(buffer, 0, length);
    printer.built(s);
    printer.drain();
    return text.toString(UTF_8);
  }

  /** The byte length of the scratch this printer keeps between values (#35 Open 3). */
  public int retainedScratch() {
    return scratch.length;
  }

  /** Prints the value at tape index {@code at} of {@code doc}, after checking that the document is live (ADR-0026). */
  private void value(Document doc, int at) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes();
    this.doc = doc;
    int sp = 0;
    walk:
    for (;;) {
      long entry = tape[at];
      int tag = Tape.tag(entry);
      if (tag == Tape.ARRAY || tag == Tape.OBJECT) {
        put(tag == Tape.ARRAY ? '[' : '{');
        // Two slots a level, so sp and the length stay even.
        if (sp == stack.length) {
          stack = Arrays.copyOf(stack, stack.length << 1);
        }
        stack[sp++] = at;
        stack[sp++] = at + 1;
      } else {
        scalar(bytes, entry);
      }
      // The value at `at` ended: print the next entry of the innermost open container, or close it. The entries of
      // the innermost container are sp / 2 levels deep.
      while (sp > 0) {
        int container = stack[sp - 2];
        int next = stack[sp - 1];
        int end = Tape.end(tape, container);
        if (Tape.tag(tape[container]) == Tape.ARRAY) {
          if (next < end) {
            if (next > container + 1) {
              put(',');
            }
            newline(sp >>> 1);
            stack[sp - 1] = Tape.next(tape, next);
            at = next;
            continue walk;
          }
          close(container, end, sp, ']');
        } else {
          while (next < end && !ObjectView.isFirst(tape, bytes, container, next)) {
            next = Tape.next(tape, next + 1);
          }
          if (next < end) {
            // The first key of an object is always printed, so any later printed key follows a comma.
            if (next > container + 1) {
              put(',');
            }
            newline(sp >>> 1);
            string(bytes, tape[next]);
            put(':');
            if (pretty) {
              put(' ');
            }
            stack[sp - 1] = Tape.next(tape, next + 1);
            at = ObjectView.lastValueOf(tape, bytes, next, end);
            continue walk;
          }
          close(container, end, sp, '}');
        }
        sp -= 2;
      }
      return;
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
   * Closes the container at tape index {@code container}, whose entries end at {@code end} and are {@code sp / 2}
   * levels deep. Pretty, a non-empty container's bracket goes on a new line at the container's own indent; an empty
   * one, and every container in compact output, closes right after its opening bracket or last entry. An object with
   * entries always prints its first key, so {@code end} tells an empty object for both kinds.
   */
  private void close(int container, int end, int sp, char bracket) {
    if (end > container + 1) {
      newline((sp >>> 1) - 1);
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
   * is host code and may have read into the same Workspace (ADR-0027). Only a document's value, a built string or a
   * filter's literal number fills the buffer: null and a boolean are at most five bytes and start in an empty one. A
   * built string and a literal number have no document.
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
