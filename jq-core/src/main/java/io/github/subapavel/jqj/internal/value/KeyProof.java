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
 * n), never n * n. No repeat leaves the walk without a plan; the first one makes it. A tag-6 key is decoded at most
 * once per proof, the first time a compare or the hash needs its bytes (#85 review).
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
    boolean escaped = false;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      n++;
      ticks += Math.max(1, kibibytes(Tape.span(tape[k]) - 2));
      escaped |= Tape.tag(tape[k]) != Tape.STRING;
    }
    if (written) {
      state.tickWritten(ticks);
    } else {
      state.tick(ticks);
    }
    // Each tag-6 key is decoded once, into its ordinal's place, when the proof first needs its bytes (#85 review).
    byte[][] texts = escaped ? new byte[n][] : null;
    Keys keys = new Keys(tape, bytes, texts);
    if (n <= PAIRWISE) {
      return pairwise(state, doc, keys, index, end, n);
    }
    ObjectWalk hashed = hashed(state, doc, keys, index, end, n);
    return hashed != null ? hashed : sorted(state, doc, keys, index, end, n, written);
  }

  /**
   * The keys of one proof: a tag-5 key's decoded bytes are the document's, a tag-6 key's are decoded once, by its
   * ordinal, the first time the proof reads them; {@code texts} is null when the object has no tag-6 key.
   */
  private record Keys(long[] tape, byte[] bytes, byte[][] texts) {
    /** The array the decoded bytes of key entry {@code ordinal}, at tape index {@code k}, lie in. */
    byte[] text(int ordinal, int k) {
      long entry = tape[k];
      if (Tape.tag(entry) == Tape.STRING) {
        return bytes;
      }
      byte[] text = texts[ordinal];
      if (text == null) {
        text = JsonStrings.decode(bytes, entry);
        texts[ordinal] = text;
      }
      return text;
    }

    /** Where the decoded bytes of the key at tape index {@code k} start in its {@link #text}. */
    int from(int k) {
      long entry = tape[k];
      return Tape.tag(entry) == Tape.STRING ? Tape.off(entry) + 1 : 0;
    }

    /** Where the decoded bytes of the key at tape index {@code k} end in {@code text}, its {@link #text}. */
    int to(byte[] text, int k) {
      long entry = tape[k];
      return Tape.tag(entry) == Tape.STRING ? Tape.off(entry) + Tape.span(entry) - 1 : text.length;
    }
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
   * scratch as a pair of its key's first place and its last value, and after the pairs the ordinal of each first place.
   */
  private static ObjectWalk pairwise(RunState state, Document doc, Keys keys, int index, int end, int n) {
    long[] tape = keys.tape();
    int[] pairs = state.ints(3 * n);
    int size = 0;
    boolean repeated = false;
    int ordinal = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      byte[] key = keys.text(ordinal, k);
      int from = keys.from(k);
      int to = keys.to(key, k);
      int at = 0;
      while (at < size) {
        int other = pairs[2 * at];
        byte[] otherKey = keys.text(pairs[2 * n + at], other);
        if (Arrays.equals(key, from, to, otherKey, keys.from(other), keys.to(otherKey, other))) {
          break;
        }
        at++;
      }
      if (at == size) {
        pairs[2 * size] = k;
        pairs[2 * n + size] = ordinal;
        size++;
      } else {
        repeated = true;
      }
      pairs[2 * at + 1] = k + 1;
      ordinal++;
    }
    return new ObjectWalk(doc, index, size, repeated ? Arrays.copyOf(pairs, 2 * size) : null);
  }

  /**
   * The walk of {@code n} key entries proved through the table, or null when the probe budget runs out. The scratch
   * holds the table's slots first, each 0 when free or one more than the number of a distinct key, then a pair per
   * distinct key, its first place and its last value, then the ordinal of each distinct key's first place.
   */
  private static ObjectWalk hashed(RunState state, Document doc, Keys keys, int index, int end, int n) {
    long[] tape = keys.tape();
    int slots = slots(n);
    int mask = slots - 1;
    int[] scratch = state.ints(slots + 3 * n);
    int ordinals = slots + 2 * n;
    Arrays.fill(scratch, 0, slots, 0);
    long budget = (long) PROBES_PER_KEY * n;
    int size = 0;
    boolean repeated = false;
    int ordinal = 0;
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      byte[] key = keys.text(ordinal, k);
      int from = keys.from(k);
      int to = keys.to(key, k);
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
          scratch[ordinals + size - 1] = ordinal;
          break;
        }
        int other = scratch[slots + 2 * held - 2];
        byte[] otherKey = keys.text(scratch[ordinals + held - 1], other);
        int otherFrom = keys.from(other);
        int otherTo = keys.to(otherKey, other);
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
      ordinal++;
    }
    return new ObjectWalk(doc, index, size, repeated ? Arrays.copyOfRange(scratch, slots, slots + 2 * size) : null);
  }

  /**
   * The walk of {@code n} key entries proved by a sort. The scratch holds the key entries' tape indices in source order,
   * then their ordinals, sorted stably by their keys' bytes, then the merge's other half, which afterwards holds, at the
   * ordinal of each distinct key's first place, the tape index of its last value, and 0 at a repeat's.
   */
  private static ObjectWalk sorted(RunState state, Document doc, Keys keys, int index, int end, int n, boolean written) {
    long[] tape = keys.tape();
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
        merge(state, keys, scratch, from, to, lo, mid, hi, written);
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
      while (next < n && compare(state, keys, scratch, first, scratch[from + next], written) == 0) {
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
  private static void merge(RunState state, Keys keys, int[] scratch, int from, int to, int lo, int mid, int hi, boolean written) {
    int i = lo;
    int j = mid;
    int k = lo;
    while (i < mid && j < hi) {
      int left = scratch[from + i];
      int right = scratch[from + j];
      if (compare(state, keys, scratch, left, right, written) > 0) {
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

  /**
   * The order of the keys of ordinals {@code a} and {@code b}, whose tape indices {@code scratch} holds at those places,
   * by their unsigned decoded bytes: one sort compare.
   */
  private static int compare(RunState state, Keys keys, int[] scratch, int a, int b, boolean written) {
    int x = scratch[a];
    int y = scratch[b];
    byte[] p = keys.text(a, x);
    byte[] q = keys.text(b, y);
    int pf = keys.from(x);
    int pt = keys.to(p, x);
    int qf = keys.from(y);
    int qt = keys.to(q, y);
    int ticks = Math.max(1, kibibytes(Math.min(pt - pf, qt - qf)));
    if (written) {
      state.tickWritten(ticks);
    } else {
      state.tick(ticks);
    }
    return Arrays.compareUnsigned(p, pf, pt, q, qf, qt);
  }

  /** The table's slots for {@code n} key entries, past {@link #PAIRWISE}: the least power of two of 4 n, at most 2^20. */
  static int slots(int n) {
    return Math.min(MAX_SLOTS, Integer.highestOneBit(SLOTS_PER_KEY * n - 1) << 1);
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
