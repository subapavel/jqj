package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;

/**
 * Copies every view in a value into built values (GLOSSARY "Detach", ADR-0016, ADR-0039), so the copy outlives its
 * document: a document's string becomes a built string of its decoded bytes, a number a literal of its token, and each
 * container, a document's or a built one, a built container of its members' copies, an object its keys' too, in its
 * members' order. The walk keeps an explicit stack, so a deep value takes no Java stack. It runs under the host rule
 * of ADR-0016, after the run: it charges no bytes built and no tick.
 */
public final class Detach {
  /** The capacity of a container's first array of copies. */
  private static final int FIRST = 4;

  /** The cursor {@link Members} gives when there is no member (left). */
  private static final int END = -1;

  private static final Object[] NONE = {};

  private Detach() {}

  /**
   * The copy of {@code value} that holds no view.
   *
   * @throws io.github.subapavel.jqj.JqTermination with cause {@code STALE_VIEW} when a view in it is stale
   */
  public static JqValue of(JqValue value) {
    // The walks charge their scans to a run state no run began: every budget of it is unlimited.
    RunState free = new RunState();
    Frame top = null;
    JqValue next = value;
    for (;;) {
      JqValue copied;
      if (next instanceof Members container) {
        Frame frame = new Frame(container, top);
        int first = container.first();
        if (first != END) {
          frame.cursor = first;
          top = frame;
          next = container.member(free, first);
          continue;
        }
        copied = frame.build();
      } else {
        copied = leaf(next);
      }
      // Hand the copy to its container; each container whose last member it was is built and handed on in turn.
      for (;;) {
        if (top == null) {
          return copied;
        }
        top.add(copied);
        int cursor = top.container.next(free, top.cursor);
        if (cursor != END) {
          top.cursor = cursor;
          next = top.container.member(free, cursor);
          break;
        }
        copied = top.build();
        top = top.parent;
      }
    }
  }

  /** The copy of a value that is no container: a document's string or number copied, any other as it is. */
  private static JqValue leaf(JqValue v) {
    return switch (v) {
      case StringView s -> string(s);
      case NumberView n -> LiteralNumber.copyOf(n);
      // Null, the booleans, built strings and the filter's and computed numbers view no document.
      default -> v;
    };
  }

  /** A string as a built string: a document's of its decoded bytes, a built one as it is. */
  private static JqString key(JqString key) {
    return key instanceof StringView s ? string(s) : key;
  }

  private static BuiltString string(StringView s) {
    long entry = s.doc().live()[s.index()];
    return BuiltString.of(JsonStrings.decode(s.doc().bytes(), entry));
  }

  /** A container on the stack: the copies of its members so far, and for an object their keys. */
  private static final class Frame {
    final Members container;
    final Frame parent;

    /** The cursor of the member being copied. */
    int cursor;

    Object[] values = NONE;

    /** The copied keys, for an object; null for an array. */
    JqString[] keys;

    int length;

    Frame(Members container, Frame parent) {
      this.container = container;
      this.parent = parent;
      this.keys = container instanceof Keyed ? new JqString[0] : null;
    }

    /** Adds the copy of the member at the cursor, and for an object the copy of its key. */
    void add(JqValue copied) {
      if (length == values.length) {
        int capacity = Math.max(FIRST, 2 * length);
        values = Arrays.copyOf(values, capacity);
        if (keys != null) {
          keys = Arrays.copyOf(keys, capacity);
        }
      }
      if (keys != null) {
        keys[length] = key(((Keyed) container).key(cursor));
      }
      values[length++] = copied;
    }

    /** The built container of the copies; an object's keys are distinct, as its members' walk gives them. */
    JqValue build() {
      return keys == null ? new BuiltArray(values, length) : new BuiltObject(keys, values, length);
    }
  }
}
