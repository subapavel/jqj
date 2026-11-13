package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Copies every view in a value into built values (GLOSSARY "Detach", ADR-0016, ADR-0039), so the copy outlives its
 * document: a document's string becomes a built string of its decoded bytes, a number a literal of its token, and each
 * container, a document's or a built one, a built container of its members' copies, an object its keys' too, in its
 * members' order. The walk keeps an explicit stack, so a deep value takes no Java stack. It runs under the host rule
 * of ADR-0016, after the run, where no budget, deadline or interrupt reaches it: it charges no bytes built and no
 * tick, and its time is linear in the value's size. A document's object is read once, its keys decoded into a map, so
 * a repeated key keeps its first position and takes its last value without the scans of {@link ObjectView#next}.
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
    // The walks of built containers and arrays charge their scans to a run state no run began: every budget of it is
    // unlimited, and none of those walks scans.
    RunState free = new RunState();
    Frame top = null;
    JqValue next = value;
    for (;;) {
      JqValue copied;
      if (next instanceof Members container) {
        Frame frame = Frame.of(container, top, free);
        if (frame.length > 0) {
          top = frame;
          next = (JqValue) frame.values[0];
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
        top.values[top.at++] = copied;
        if (top.at < top.length) {
          next = (JqValue) top.values[top.at];
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

  /**
   * A container on the stack: its members, each replaced by its copy in turn, and for an object their keys' copies.
   */
  private static final class Frame {
    final Frame parent;

    /** The members, {@code [0, length)}: the copies before {@link #at}, the members still to copy from it on. */
    Object[] values = NONE;

    /** The copied keys, for an object; null for an array. */
    JqString[] keys;

    int length;

    /** The member being copied. */
    int at;

    private Frame(Frame parent, boolean keyed) {
      this.parent = parent;
      this.keys = keyed ? new JqString[0] : null;
    }

    /** The frame of {@code container}'s members, each object key once, at its first position with its last value. */
    static Frame of(Members container, Frame parent, RunState free) {
      Frame frame = new Frame(parent, container instanceof Keyed);
      if (container instanceof ObjectView object) {
        frame.addDistinct(object);
      } else {
        // An array's or a built object's walk steps from member to member: a built object's keys are distinct.
        for (int c = container.first(); c != END; c = container.next(free, c)) {
          frame.add(container instanceof Keyed keyed ? key(keyed.key(c)) : null, container.member(free, c));
        }
      }
      return frame;
    }

    /**
     * A document's object, read once: each key decoded and looked up in a map of the keys so far (ObjectView: keys are
     * one when their decoded bytes are), a new one added, a repeated one's value replaced.
     */
    private void addDistinct(ObjectView object) {
      Document doc = object.doc();
      long[] tape = doc.live();
      byte[] bytes = doc.bytes();
      int end = Tape.end(tape, object.index());
      Map<ByteBuffer, Integer> seen = new HashMap<>();
      for (int k = object.index() + 1; k < end; k = Tape.next(tape, k + 1)) {
        byte[] name = JsonStrings.decode(bytes, tape[k]);
        JqValue member = doc.valueAt(tape, k + 1);
        Integer first = seen.putIfAbsent(ByteBuffer.wrap(name), length);
        if (first == null) {
          add(BuiltString.of(name), member);
        } else {
          values[first] = member;
        }
      }
    }

    /** Adds a member, and for an object the copy of its key. */
    private void add(JqString key, JqValue member) {
      if (length == values.length) {
        int capacity = Math.max(FIRST, 2 * length);
        values = Arrays.copyOf(values, capacity);
        if (keys != null) {
          keys = Arrays.copyOf(keys, capacity);
        }
      }
      if (keys != null) {
        keys[length] = key;
      }
      values[length++] = member;
    }

    /** The built container of the copies; an object's keys are distinct, as {@link #of} gives them. */
    JqValue build() {
      return keys == null ? new BuiltArray(values, length) : new BuiltObject(keys, values, length);
    }
  }
}
