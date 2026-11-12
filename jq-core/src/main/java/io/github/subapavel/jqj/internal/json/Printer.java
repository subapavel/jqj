package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.Document;
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
 * a Stub otherwise (#36); a tag-6 string and a built string are Stubs (#35). An object prints each key at its first position with its
 * last value (ADR-0002). A Stub met in the middle of a value leaves what was drained before it written.
 *
 * <p>Pretty (#37) changes only the layout of containers: each entry of a non-empty container starts on a new line,
 * indented by two spaces a level, a key is followed by {@code ": "}, and the closing bracket is on its own line at the
 * container's indent. An empty container is {@code []} or {@code {}}, as in compact output.
 */
public final class Printer {
  /** Where the buffer drains: host code, which may read into the Workspace whose document is being printed. */
  public interface Target {
    void drain(byte[] buffer, int length);
  }

  private static final int CAPACITY = 64 * 1024;
  private static final byte[] NULL = {'n', 'u', 'l', 'l'};
  private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
  private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};

  /** jq's default indent: two spaces a level. */
  private static final int INDENT = 2;

  /** The spaces an indent is copied from, a chunk at a time. */
  private static final byte[] SPACES = " ".repeat(256).getBytes(UTF_8);

  private final byte[] buffer = new byte[CAPACITY];

  /** Whether the value being printed is laid out as jq's default pretty output; false for compact. */
  private boolean pretty;

  /** Two slots per open container: its tape index, then the index of its next entry to print. */
  private int[] stack = new int[32];

  private int pos;
  private Target target;

  /** The document being printed, checked again after a drain in the middle of a value. */
  private Document doc;

  /** Prints {@code v}, pretty or compact, and one LF, then drains everything into {@code target} (ADR-0027). */
  public void line(JqValue v, boolean pretty, Target target) {
    this.pretty = pretty;
    this.target = target;
    // A value that ended in a Stub or a target's exception may have left bytes behind.
    pos = 0;
    switch (v) {
      case JqNull _ -> put(NULL);
      case JqBool b -> put(b.value() ? TRUE : FALSE);
      case NumberView n -> value(n.doc(), n.index());
      case StringView s -> value(s.doc(), s.index());
      case BuiltString _ -> throw Stub.unbuilt("printing a built string");
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
            long key = tape[next];
            put(bytes, Tape.off(key), Tape.span(key));
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
        if ((span & Tape.CANONICAL) == 0) {
          throw Stub.unbuilt("printing a number in jq's canonical form");
        }
        put(bytes, Tape.off(entry), span & Tape.NUMBER_LENGTH);
      }
      case Tape.STRING_REENCODE -> throw Stub.unbuilt("printing a string that must be re-encoded");
      // Tape.NULL, FALSE, TRUE and STRING: a scalar's span is its byte length (ADR-0002).
      default -> put(bytes, Tape.off(entry), span);
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
  private void put(byte[] src, int off, int length) {
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

  private void put(char c) {
    if (pos == CAPACITY) {
      drainFull();
    }
    buffer[pos++] = (byte) c;
  }

  /**
   * Drains a full buffer in the middle of a value, then checks the document again: the target is host code and may
   * have read into the same Workspace (ADR-0027). Only a document's value fills the buffer: a literal is at most five
   * bytes and starts in an empty one.
   */
  private void drainFull() {
    drain();
    doc.live();
  }

  private void drain() {
    target.drain(buffer, pos);
    pos = 0;
  }
}
