package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import java.util.Arrays;

/**
 * The key proof of a document's object (ADR-0044, GLOSSARY "Key proof"): at the start of each walk it finds which of
 * its key entries repeat an earlier one by their decoded bytes, and gives the {@link ObjectWalk} that takes each key
 * once, at its first place with its last value, as jq does (ADR-0002). What it finds lives as long as that walk.
 *
 * <p>Up to {@link #PAIRWISE} key entries, each is compared with the distinct keys before it. Past that, the keys go
 * into an open-addressing table in the run state's scratch ({@link RunState#ints}), a power of two of at least
 * {@link #SLOTS_PER_KEY} slots per key entry and at most {@link #MAX_SLOTS}, each key from its home slot, its
 * {@link #hash} masked, to the next free one. The whole proof may probe {@link #PROBES_PER_KEY} slots per key entry; a
 * probe that compares the bytes of two keys of one length costs one per started KiB of them, at least one. Past that
 * budget (keys built to collide) it sorts the key entries instead, by their unsigned decoded bytes with a stable
 * bottom-up merge sort, jqj's (ADR-0012, F-48), and equal neighbours are repeats. So a proof takes O(key bytes + n log
 * n), never n * n. No repeat leaves the walk without a plan; the first one makes it.
 *
 * <p>Ticks (ADR-0008 as amended by ADR-0044): one per key entry, or one per started KiB of a longer key's text, and
 * one per compare of the sort, one per started KiB of the shorter key, at least one; the table's probes charge none.
 * A writer's proof charges through {@link RunState#tickWritten}. A host's accessor proves on a run state of its own, so
 * it charges no run.
 */
public final class KeyProof {
  /** The most key entries compared pairwise, without a table. */
  static final int PAIRWISE = 8;

  /** The table's least slots per key entry. */
  static final int SLOTS_PER_KEY = 4;

  /** The table's most slots. */
  static final int MAX_SLOTS = 1 << 20;

  /** The probes the whole proof may make per key entry before it sorts instead. */
  static final int PROBES_PER_KEY = 4;

  private KeyProof() {}

  /** The walk of {@code object}, a document's object, after its proof; for {@code written} the writer's ticks. */
  public static ObjectWalk walk(RunState state, ObjectView object, boolean written) {
    return walk(state, object.doc(), object.index(), written);
  }

  /** The walk of the object at tape index {@code index} of {@code doc}, after its proof. */
  public static ObjectWalk walk(RunState state, Document doc, int index, boolean written) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes();
    int end = Tape.end(tape, index);
    int n = 0;
    int ticks = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      n++;
      ticks += Math.max(1, kibibytes(Tape.span(tape[k]) - 2));
    }
    if (written) {
      state.tickWritten(ticks);
    } else {
      state.tick(ticks);
    }
    if (n <= PAIRWISE) {
      return pairwise(state, doc, tape, bytes, index, end, n);
    }
    ObjectWalk hashed = hashed(state, doc, tape, bytes, index, end, n);
    return hashed != null ? hashed : sorted(state, doc, tape, bytes, index, end, n, written);
  }

  /** The members of {@code object} for a filter's walk: a document's after its proof, a built one's as they are. */
  public static Members members(RunState state, JqObject object) {
    return switch (object) {
      case ObjectView view -> walk(state, view, false);
      case BuiltObject built -> built;
    };
  }

  /**
   * The walk of up to {@link #PAIRWISE} key entries: each compared with the distinct keys before it, kept in the
   * scratch as a pair of its key's first place and its last value.
   */
  private static ObjectWalk pairwise(RunState state, Document doc, long[] tape, byte[] bytes, int index, int end, int n) {
    int[] pairs = state.ints(2 * n);
    int size = 0;
    boolean repeated = false;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      int at = 0;
      while (at < size && !same(tape, bytes, pairs[2 * at], k)) {
        at++;
      }
      if (at == size) {
        pairs[2 * size] = k;
        size++;
      } else {
        repeated = true;
      }
      pairs[2 * at + 1] = k + 1;
    }
    return new ObjectWalk(doc, index, size, repeated ? Arrays.copyOf(pairs, 2 * size) : null);
  }

  /**
   * The walk of {@code n} key entries proved through the table, or null when the probe budget runs out. The scratch
   * holds the table's slots first, each 0 when free or one more than the number of a distinct key, then a pair per
   * distinct key, its first place and its last value.
   */
  private static ObjectWalk hashed(RunState state, Document doc, long[] tape, byte[] bytes, int index, int end, int n) {
    int slots = Math.min(MAX_SLOTS, Integer.highestOneBit(SLOTS_PER_KEY * n - 1) << 1);
    int mask = slots - 1;
    int[] scratch = state.ints(slots + 2 * n);
    Arrays.fill(scratch, 0, slots, 0);
    long budget = (long) PROBES_PER_KEY * n;
    int size = 0;
    boolean repeated = false;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      long entry = tape[k];
      byte[] key = text(bytes, entry);
      int from = from(key, entry);
      int to = to(key, entry);
      int slot = hash(key, from, to) & mask;
      for (;;) {
        int held = scratch[slot];
        if (held == 0) {
          if (--budget < 0) {
            return null;
          }
          scratch[slot] = ++size;
          scratch[slots + 2 * size - 2] = k;
          scratch[slots + 2 * size - 1] = k + 1;
          break;
        }
        int other = scratch[slots + 2 * held - 2];
        long otherEntry = tape[other];
        byte[] otherKey = text(bytes, otherEntry);
        int otherFrom = from(otherKey, otherEntry);
        int otherTo = to(otherKey, otherEntry);
        budget -= to - from == otherTo - otherFrom ? Math.max(1, kibibytes(to - from)) : 1;
        if (budget < 0) {
          return null;
        }
        if (Arrays.equals(key, from, to, otherKey, otherFrom, otherTo)) {
          scratch[slots + 2 * held - 1] = k + 1;
          repeated = true;
          break;
        }
        slot = (slot + 1) & mask;
      }
    }
    return new ObjectWalk(doc, index, size, repeated ? Arrays.copyOfRange(scratch, slots, slots + 2 * size) : null);
  }

  /**
   * The walk of {@code n} key entries proved by a sort. The scratch holds the key entries' tape indices in source order,
   * then their ordinals, sorted stably by their keys' bytes, then the merge's other half, which afterwards holds, at the
   * ordinal of each distinct key's first place, the tape index of its last value, and 0 at a repeat's.
   */
  private static ObjectWalk sorted(RunState state, Document doc, long[] tape, byte[] bytes, int index, int end, int n, boolean written) {
    int[] scratch = state.ints(3 * n);
    int i = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      scratch[i] = k;
      scratch[n + i] = i;
      i++;
    }
    int from = n;
    int to = 2 * n;
    for (int width = 1; width < n; width *= 2) {
      for (int lo = 0; lo < n; lo += 2 * width) {
        int mid = Math.min(lo + width, n);
        int hi = (int) Math.min((long) lo + 2 * width, n);
        merge(state, tape, bytes, scratch, from, to, lo, mid, hi, written);
      }
      int merged = to;
      to = from;
      from = merged;
    }
    // `from` holds the sorted ordinals, `to` is free for the last values.
    Arrays.fill(scratch, to, to + n, 0);
    int size = 0;
    int start = 0;
    while (start < n) {
      int first = scratch[from + start];
      int next = start + 1;
      while (next < n && compare(state, tape, bytes, scratch[scratch[from + start]], scratch[scratch[from + next]], written) == 0) {
        next++;
      }
      scratch[to + first] = scratch[scratch[from + next - 1]] + 1;
      size++;
      start = next;
    }
    if (size == n) {
      return new ObjectWalk(doc, index, n, null);
    }
    int[] plan = new int[2 * size];
    int p = 0;
    for (int ordinal = 0; ordinal < n; ordinal++) {
      int value = scratch[to + ordinal];
      if (value != 0) {
        plan[p++] = scratch[ordinal];
        plan[p++] = value;
      }
    }
    return new ObjectWalk(doc, index, size, plan);
  }

  /**
   * Merges the sorted runs of ordinals {@code [lo, mid)} and {@code [mid, hi)} at {@code from} into {@code to}: the left
   * run's ordinal first unless the right one's key comes strictly before it, so equal keys keep their source order.
   */
  private static void merge(RunState state, long[] tape, byte[] bytes, int[] scratch, int from, int to, int lo, int mid, int hi, boolean written) {
    int i = lo;
    int j = mid;
    int k = lo;
    while (i < mid && j < hi) {
      int left = scratch[from + i];
      int right = scratch[from + j];
      if (compare(state, tape, bytes, scratch[left], scratch[right], written) > 0) {
        scratch[to + k++] = right;
        j++;
      } else {
        scratch[to + k++] = left;
        i++;
      }
    }
    System.arraycopy(scratch, from + i, scratch, to + k, mid - i);
    System.arraycopy(scratch, from + j, scratch, to + k + mid - i, hi - j);
  }

  /** The order of the keys at tape indices {@code a} and {@code b} by their unsigned decoded bytes: one sort compare. */
  private static int compare(RunState state, long[] tape, byte[] bytes, int a, int b, boolean written) {
    long x = tape[a];
    long y = tape[b];
    byte[] p = text(bytes, x);
    byte[] q = text(bytes, y);
    int pf = from(p, x);
    int pt = to(p, x);
    int qf = from(q, y);
    int qt = to(q, y);
    int ticks = Math.max(1, kibibytes(Math.min(pt - pf, qt - qf)));
    if (written) {
      state.tickWritten(ticks);
    } else {
      state.tick(ticks);
    }
    return Arrays.compareUnsigned(p, pf, pt, q, qf, qt);
  }

  /** Whether the keys at tape indices {@code a} and {@code b} are one: their decoded bytes are equal. */
  static boolean same(long[] tape, byte[] bytes, int a, int b) {
    long x = tape[a];
    long y = tape[b];
    byte[] p = text(bytes, x);
    byte[] q = text(bytes, y);
    return Arrays.equals(p, from(p, x), to(p, x), q, from(q, y), to(q, y));
  }

  /** The array a key's decoded bytes lie in: a tag-5 key's are the document's, a tag-6 key's a decoded copy. */
  private static byte[] text(byte[] bytes, long entry) {
    return Tape.tag(entry) == Tape.STRING ? bytes : JsonStrings.decode(bytes, entry);
  }

  /** Where a key's decoded bytes start in {@code key}, its {@link #text}. */
  private static int from(byte[] key, long entry) {
    return Tape.tag(entry) == Tape.STRING ? Tape.off(entry) + 1 : 0;
  }

  /** Where a key's decoded bytes end in {@code key}, its {@link #text}. */
  private static int to(byte[] key, long entry) {
    return Tape.tag(entry) == Tape.STRING ? Tape.off(entry) + Tape.span(entry) - 1 : key.length;
  }

  /** The started KiB of {@code bytes} bytes. */
  private static int kibibytes(int bytes) {
    return (bytes + 1023) >>> 10;
  }

  /**
   * The proof's hash of the decoded bytes {@code key[from, to)}, a function of them alone: a 31-polynomial, then
   * MurmurHash3's finaliser (public domain), which spreads its high bits into the low ones that make the home slot. It
   * has no seed, so a run's ticks never depend on one; keys built to collide in it end in the sort.
   */
  static int hash(byte[] key, int from, int to) {
    int h = 0;
    for (int i = from; i < to; i++) {
      h = 31 * h + key[i];
    }
    h = (h ^ (h >>> 16)) * 0x85EB_CA6B;
    h = (h ^ (h >>> 13)) * 0xC2B2_AE35;
    return h ^ (h >>> 16);
  }
}
