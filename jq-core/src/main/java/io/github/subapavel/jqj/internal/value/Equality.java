/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jvp_equal, jvp_array_equal and jvp_object_equal in
 * src/jv.c.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's {@code jv_equal} (ADR-0029, design approval K1 of #65): depth first, then left to right, each pair of
 * values at a depth, the outermost at 0. A pair deeper than {@link #MAX_DEPTH} raises jq's catchable "Equality check
 * too deep" before anything else is compared; then two kinds differ (true and false are two kinds); then one container
 * is itself, which mirrors jq's pointer check (O4): the same built container, or a view of the same document entry,
 * whose document is checked first (ADR-0026). Numbers compare by {@link Numbers#compare}, strings by their UTF-8 bytes,
 * arrays by their lengths, then element by element; an object by each of its keys, in its key order, found in the
 * other object with an equal value, and then by the two key counts. The walk keeps its open containers on a stack of
 * its own, never in Java frames, and charges one tick per member pair it compares (ADR-0008); a view's own walks
 * charge theirs.
 */
public final class Equality {
  /** jq's {@code MAX_EQUAL_DEPTH}. */
  static final int MAX_DEPTH = 10_000;

  private static final String TOO_DEEP = "Equality check too deep";

  private Equality() {}

  /**
   * Whether {@code a} equals {@code b}, as jq's {@code jv_equal} tells.
   *
   * @throws FilterException "Equality check too deep", jq's error
   */
  public static boolean equal(RunState state, JqValue a, JqValue b) {
    JqValue x = a;
    JqValue y = b;
    int depth = 0;
    Walk open = null;
    for (;;) {
      if (depth > MAX_DEPTH) {
        throw new FilterException(BuiltString.of(TOO_DEEP));
      }
      Walk inner = null;
      boolean same =
          switch (x) {
            case JqNull _ -> y instanceof JqNull;
            case JqBool _ -> x == y;
            case JqNumber n -> y instanceof JqNumber m && Numbers.compare(n, m) == 0;
            case JqString s -> y instanceof JqString t && s.utf8().mismatch(t.utf8()) == -1;
            case JqArray p -> {
              if (!(y instanceof JqArray q) || length(state, p) != length(state, q)) {
                yield false;
              }
              if (!p.equals(q)) {
                inner = new Elements((Members) p, (Members) q, depth + 1);
              }
              yield true;
            }
            case JqObject p -> {
              if (!(y instanceof JqObject q)) {
                yield false;
              }
              int first = ((Members) p).first();
              if (!p.equals(q)) {
                inner = new Values((Members) p, (Keyed) q, first, depth + 1);
              }
              yield true;
            }
          };
      if (!same) {
        return false;
      }
      if (inner != null) {
        inner.outer = open;
        open = inner;
      }
      for (;;) {
        if (open == null) {
          return true;
        }
        int next = open.next(state);
        if (next == Walk.UNEQUAL) {
          return false;
        }
        if (next == Walk.PAIR) {
          state.tick();
          x = open.x;
          y = open.y;
          depth = open.depth;
          break;
        }
        open = open.outer;
      }
    }
  }

  /** An array's length; a view's walk charges its elements (ADR-0034). */
  private static int length(RunState state, JqArray array) {
    return switch (array) {
      case ArrayView view -> view.length(state);
      case BuiltArray built -> built.length();
    };
  }

  /** One open pair of containers: the pairs of members it gives, one at a time, each at {@link #depth}. */
  private abstract static class Walk {
    /** {@link #next}: {@link #x} and {@link #y} are the next pair. */
    static final int PAIR = 0;

    /** {@link #next}: no pair is left, and the containers are equal so far. */
    static final int DONE = 1;

    /** {@link #next}: the containers are not equal. */
    static final int UNEQUAL = 2;

    final int depth;

    /** The walk around this one, or null for the outermost. */
    Walk outer;

    JqValue x;
    JqValue y;

    Walk(int depth) {
      this.depth = depth;
    }

    abstract int next(RunState state);
  }

  /** Two arrays of one length, element by element. */
  private static final class Elements extends Walk {
    private final Members a;
    private final Members b;
    private int ca;
    private int cb;

    Elements(Members a, Members b, int depth) {
      super(depth);
      this.a = a;
      this.b = b;
      this.ca = a.first();
      this.cb = b.first();
    }

    @Override
    int next(RunState state) {
      if (ca == -1) {
        return DONE;
      }
      x = a.member(state, ca);
      y = b.member(state, cb);
      ca = a.next(state, ca);
      cb = b.next(state, cb);
      return PAIR;
    }
  }

  /**
   * jq's {@code jvp_object_equal}: each key of the left object in its key order with its value, the right object's value
   * of the same key; a key the right object lacks makes them unequal at once. After the last key they are equal when
   * the right object has no other key.
   */
  private static final class Values extends Walk {
    private final Members left;
    private final Keyed right;
    private int cursor;
    private int matched;

    Values(Members left, Keyed right, int first, int depth) {
      super(depth);
      this.left = left;
      this.right = right;
      this.cursor = first;
    }

    @Override
    int next(RunState state) {
      if (cursor == -1) {
        Members other = (Members) right;
        int keys = 0;
        for (int c = other.first(); c != -1; c = other.next(state, c)) {
          keys++;
        }
        return keys == matched ? DONE : UNEQUAL;
      }
      JqValue value = right.get(state, ((Keyed) left).key(cursor).value());
      if (value == null) {
        return UNEQUAL;
      }
      x = left.member(state, cursor);
      y = value;
      cursor = left.next(state, cursor);
      matched++;
      return PAIR;
    }
  }
}
