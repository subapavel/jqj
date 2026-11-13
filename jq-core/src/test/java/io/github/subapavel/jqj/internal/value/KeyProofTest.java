package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * ADR-0044's key proof at its seam, {@link KeyProof#walk}, on a run state of the test's own: which proof runs, what its
 * scratch takes and what it charges. Up to 8 key entries compare pairwise in a scratch of 3 n ints; past 8, the table
 * takes the least power of two of at least 4 n slots, at most 2^20, plus 3 n ints; a proof that probes past 4 n slots
 * sorts the key entries instead, one tick per compare. The colliding keys are found against the proof's own
 * {@link KeyProof#hash}: in a table of 64 slots, a cluster of c keys of one home slot takes 1 + 2 + … + c probes, and
 * every other key one, when no other key's home slot lies in the cluster or just after it. The objects lay their keys
 * out in ascending byte order, so the sort's compares are known: a bottom-up merge of sorted runs compares each
 * element of its left run once, and then each distinct key once with the next.
 */
class KeyProofTest {
  /** The table's mask for 9 to 16 key entries: 64 slots. */
  private static final int MASK = 63;

  /** The home slot of every cluster. */
  private static final int HOME = 0;

  /** The read object of {@code json}, on a Workspace of its own. */
  private static ObjectView object(String json) {
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    return (ObjectView) workspace.read(MemorySegment.ofArray(json.getBytes(US_ASCII))).root();
  }

  /** {@code {"k0":0,…}}: the keys in the order given, each value its place. */
  private static String json(List<String> keys) {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = 0; i < keys.size(); i++) object.add("\"" + keys.get(i) + "\":" + i);
    return object.toString();
  }

  /** The proof's home slot of {@code key} in a table of 64 slots. */
  private static int home(String key) {
    byte[] bytes = key.getBytes(US_ASCII);
    return KeyProof.hash(bytes, 0, bytes.length) & MASK;
  }

  /** The first {@code count} keys {@code prefix} + a five-digit number whose home slot is {@link #HOME}. */
  private static List<String> cluster(String prefix, int count) {
    List<String> keys = new ArrayList<>();
    for (int i = 0; keys.size() < count; i++) {
      String key = prefix + String.format("%05d", i);
      if (home(key) == HOME) keys.add(key);
    }
    return keys;
  }

  /** Three keys {@code o} + a five-digit number whose home slots differ and lie in the table's upper half, away from any cluster. */
  private static List<String> others() {
    List<String> keys = new ArrayList<>();
    List<Integer> homes = new ArrayList<>();
    for (int i = 0; keys.size() < 3; i++) {
      String key = "o" + String.format("%05d", i);
      int home = home(key);
      if (home >= 32 && !homes.contains(home)) {
        keys.add(key);
        homes.add(home);
      }
    }
    return keys;
  }

  /** The keys of {@code a} and {@code b}, in ascending byte order: ASCII, so String order. */
  private static List<String> sorted(List<String> a, List<String> b) {
    return Stream.concat(a.stream(), b.stream()).sorted().toList();
  }

  /** The ticks of the proof of {@code object}'s walk, on a run state that charges no run. */
  private static long proofTicks(ObjectView object) {
    RunState state = new RunState();
    KeyProof.walk(state, object, false);
    return state.steps();
  }

  /** The keys and values of {@code walk}, in its order, as {@code "key=value"}. */
  private static List<String> members(ObjectWalk walk) {
    RunState free = new RunState();
    List<String> members = new ArrayList<>();
    for (int c = walk.first(); c != -1; c = walk.next(free, c)) members.add(walk.key(c).value() + "=" + walk.member(free, c).toJson());
    return members;
  }

  @Test
  void overBudgetTakesTheSort() {
    // A cluster of 9 and 3 other keys: 45 + 3 = 48 probes, the budget of 4 * 12, so the table proves them, one tick per
    // key entry. A cluster of 10 and the same 3: 55 + 3 = 58 probes, past the budget of 52, so the 13 key entries are
    // sorted: 13 entry ticks, the merges 6 + 6 + 8 + 8 = 28 compares and the 12 neighbour compares.
    List<String> nine = cluster("c", 9);
    List<String> ten = cluster("c", 10);
    byte[] first = nine.getFirst().getBytes(US_ASCII);
    byte[] last = ten.getLast().getBytes(US_ASCII);
    assertThat(KeyProof.hash(first, 0, first.length) & MASK).as("home slot of the cluster's first key").isEqualTo(KeyProof.hash(last, 0, last.length) & MASK);
    ObjectView atBudget = object(json(sorted(nine, others())));
    assertThat(proofTicks(atBudget)).as("ticks of the proof of 12 keys in 48 probes").isEqualTo(12);
    ObjectView past = object(json(sorted(ten, others())));
    assertThat(proofTicks(past)).as("ticks of the proof of 13 keys past 52 probes").isEqualTo(13 + 28 + 12);
    RunState state = new RunState();
    ObjectWalk walk = KeyProof.walk(state, past, false);
    assertThat(walk.size()).as("size of the sorted walk").isEqualTo(13);
    assertThat(walk.plan()).as("plan of 13 distinct keys after the sort").isNull();
    List<String> keys = sorted(ten, others());
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < keys.size(); i++) expected.add(keys.get(i) + "=" + i);
    assertThat(members(walk)).as("members of the sorted walk").containsExactlyElementsOf(expected);
  }

  @Test
  void aProbeThatComparesTwoLongKeysOfOneLengthCostsItsKiB() {
    // 9 keys of 2 050 bytes (3 KiB) at one home slot and 3 others: 48 probes, but each compare of two of the long keys
    // costs 3, so 9 + 3 * 36 + 3 = 120 run past the budget of 48 and the proof sorts. Entry ticks 9 * 3 + 3 = 30; the
    // sorted layout puts the 3 others first, and a compare costs 3 between two long keys, 1 with an other: the merges
    // 14 + 14 + 6 + 18 = 52, the neighbours 3 + 8 * 3 = 27.
    String prefix = "x".repeat(2045);
    List<String> longKeys = cluster(prefix, 9);
    ObjectView object = object(json(sorted(longKeys, others())));
    assertThat(proofTicks(object)).as("ticks of the proof of 9 long colliding keys and 3 others").isEqualTo(30 + 52 + 27);

    // 8 short keys at one home slot, then one long key there: the long key's 8 probes compare keys of other lengths and
    // cost one each, 36 + 9 + 3 = 48 probes, the budget: the table proves them, one tick per entry and 3 for the long.
    List<String> mixed = new ArrayList<>(cluster("c", 8));
    mixed.add(cluster(prefix, 1).getFirst());
    ObjectView atBudget = object(json(sorted(mixed, others())));
    assertThat(proofTicks(atBudget)).as("ticks of the proof of 8 short keys, a long one and 3 others").isEqualTo(8 + 3 + 3);
  }

  @Test
  void aRepeatAfterTheSortKeepsItsFirstPlaceAndItsLastValue() {
    // 16 keys of four Aa/BB blocks share the 31-polynomial, so the proof's hash: 136 probes for 18 entries, past 72.
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      StringBuilder key = new StringBuilder();
      for (int b = 0; b < 4; b++) key.append((i >> b & 1) == 1 ? "BB" : "Aa");
      keys.add(key.toString());
    }
    byte[] a = keys.getFirst().getBytes(US_ASCII);
    byte[] z = keys.getLast().getBytes(US_ASCII);
    assertThat(KeyProof.hash(a, 0, a.length)).as("hash of AaAaAaAa").isEqualTo(KeyProof.hash(z, 0, z.length));
    List<String> entries = new ArrayList<>(keys);
    entries.add(keys.get(0));
    entries.add(keys.get(7));
    ObjectWalk walk = KeyProof.walk(new RunState(), object(json(entries)), false);
    assertThat(walk.size()).as("size of 18 entries with 2 repeats").isEqualTo(16);
    assertThat(walk.plan()).as("plan of a sorted walk with repeats").isNotNull();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 16; i++) expected.add(keys.get(i) + "=" + (i == 0 ? 16 : i == 7 ? 17 : i));
    assertThat(members(walk)).as("members of the sorted walk with repeats").containsExactlyElementsOf(expected);

    // A key three times: AaAaBBAa at 3, 16 and 17 keeps its first place with the value of 17. jq-ref 1.8.2, observed
    // 2026-11-13: {"a":0,"b":1,"a":2,"c":3,"a":4} gives {"a":4,"b":1,"c":3}.
    List<String> thrice = new ArrayList<>(keys);
    thrice.add(keys.get(3));
    thrice.add(keys.get(3));
    ObjectWalk three = KeyProof.walk(new RunState(), object(json(thrice)), false);
    assertThat(three.size()).as("size of 18 entries with one key three times").isEqualTo(16);
    List<String> last = new ArrayList<>();
    for (int i = 0; i < 16; i++) last.add(keys.get(i) + "=" + (i == 3 ? 17 : i));
    assertThat(members(three)).as("members of the sorted walk with a key three times").containsExactlyElementsOf(last);
  }

  @Test
  void eachProofMakesAPlanOnlyForARepeat() {
    // Pairwise (3 entries), the table (10) and their repeats: a repeated key keeps its first place with its last value.
    ObjectWalk pairwise = KeyProof.walk(new RunState(), object("{\"a\":0,\"b\":1,\"a\":2}"), false);
    assertThat(pairwise.plan()).as("plan of a pairwise walk with a repeat").isNotNull();
    assertThat(members(pairwise)).as("members of a pairwise walk with a repeat").containsExactly("a=2", "b=1");
    assertThat(KeyProof.walk(new RunState(), object("{\"a\":0,\"b\":1}"), false).plan()).as("plan of a pairwise walk without one").isNull();
    // Escaped keys, each decoded once into its own ordinal's place: the second \u0062 is b again, found as the key of
    // its first place, not of another. jq-ref 1.8.2, observed 2026-11-13: {"\u0061":0,"\u0062":1,"\u0062":2} gives
    // {"a":0,"b":2}.
    ObjectWalk escapedRepeat = KeyProof.walk(new RunState(), object("{\"\\u0061\":0,\"\\u0062\":1,\"\\u0062\":2}"), false);
    assertThat(members(escapedRepeat)).as("members of a pairwise walk of escaped keys with a repeat").containsExactly("a=0", "b=2");
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 9; i++) keys.add("k" + i);
    keys.add("k3");
    ObjectWalk table = KeyProof.walk(new RunState(), object(json(keys)), false);
    assertThat(table.plan()).as("plan of a table walk with a repeat").isNotNull();
    assertThat(members(table)).as("members of a table walk with a repeat").containsExactly("k0=0", "k1=1", "k2=2", "k3=9", "k4=4", "k5=5", "k6=6", "k7=7", "k8=8");
    assertThat(KeyProof.walk(new RunState(), object(json(keys.subList(0, 9))), false).plan()).as("plan of a table walk without one").isNull();
    assertThat(KeyProof.walk(new RunState(), object("{}"), false).first()).as("first cursor of an empty object").isEqualTo(-1);
  }

  @Test
  void eightKeysComparePairwiseAndNineTakeATable() {
    // The scratch a proof asks of a new run state: 3 n ints pairwise (the pairs, then each first place's ordinal, #85
    // review), the table's slots and 3 n past 8. 9 keys: 64 slots (the least power of two of 36). 300 000 keys: 2^20
    // slots, under 4 * 300 000; with 900 000 more ints that passes the 2^20 the run state keeps, so the proof's scratch
    // is its own and the run state keeps none (RunStateTest).
    assertThat(scratchAfter(8)).as("scratch after a proof of 8 keys").isEqualTo(24);
    assertThat(scratchAfter(9)).as("scratch after a proof of 9 keys").isEqualTo(64 + 27);
    assertThat(KeyProof.slots(300_000)).as("table slots for 300 000 keys").isEqualTo(1 << 20);
    assertThat(KeyProof.slots(1 << 16)).as("table slots for 2^16 keys, 4 n a power of two").isEqualTo(1 << 18);
    assertThat(scratchAfter(300_000)).as("scratch kept after a proof of 300 000 keys").isZero();
  }

  /** The length of a new run state's scratch after the proof of {@code n} keys {@code k0} to {@code kn-1}. */
  private static int scratchAfter(int n) {
    List<String> keys = new ArrayList<>(n);
    for (int i = 0; i < n; i++) keys.add("k" + i);
    RunState state = new RunState();
    KeyProof.walk(state, object(json(keys)), false);
    return state.ints(0).length;
  }

  /** 174 763 escapes {@code \\u00e9} or {@code \\u00ff}: a tag-6 key of 1 MiB in the document, 349 526 bytes decoded. */
  private static final int ESCAPES = 174_763;

  /** The key text of {@link #ESCAPES} escapes of {@code hex}, as the document holds it. */
  private static String escaped(String hex) {
    return ("\\u00" + hex).repeat(ESCAPES);
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void theTableDecodesAnEscapedKeyOnce() {
    // #85 review: a tag-6 key was decoded again on every probe that met it. 2^15 key entries: the 1 MiB escaped key, then
    // one short key h whose home slot in the table of 2^17 slots is the escaped key's, 2^15 - 1 times. Each h after the
    // first probes the escaped key's slot, then finds itself in the next: 2 probes, 2^16 in all, under the budget of
    // 2^17, so the table proves them. Decoded once, the proof reads about 1 MiB; decoded per probe, 32 GiB.
    int n = 1 << 15;
    int mask = (1 << 17) - 1;
    byte[] big = "é".repeat(ESCAPES).getBytes(UTF_8);
    int home = KeyProof.hash(big, 0, big.length) & mask;
    String h = null;
    for (int i = 0; h == null; i++) {
      byte[] candidate = ("h" + i).getBytes(US_ASCII);
      if ((KeyProof.hash(candidate, 0, candidate.length) & mask) == home) h = "h" + i;
    }
    StringBuilder json = new StringBuilder(8 << 20).append("{\"").append(escaped("e9")).append("\":0");
    for (int i = 1; i < n; i++) json.append(",\"").append(h).append("\":").append(i);
    ObjectWalk walk = KeyProof.walk(new RunState(), object(json.append('}').toString()), false);
    assertThat(walk.size()).as("size of the escaped key and one key repeated").isEqualTo(2);
    assertThat(walk.member(new RunState(), walk.next(new RunState(), walk.first())).toJson()).as("h's last value").isEqualTo(Integer.toString(n - 1));
  }

  @Test
  @Timeout(value = 5, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void theSortDecodesAnEscapedKeyOnce() {
    // #85 review: a tag-6 key was decoded again on every sort compare. The 1 MiB escaped key of U+00FF sorts after the
    // 2^15 keys of 15 Aa/BB blocks, which share one hash, so the proof sorts. The blocks go high bit first, so those keys
    // ascend in the source; the escaped key, first, is the last of its left run at every merge level, and meets each
    // key of the right run: 2^15 - 1 compares in all. Decoded once, the proof reads about 2 MiB; per compare, 32 GiB.
    int n = 1 << 15;
    StringBuilder json = new StringBuilder(4 << 20).append("{\"").append(escaped("ff")).append("\":0");
    for (int i = 0; i < n; i++) {
      json.append(",\"");
      for (int b = 14; b >= 0; b--) json.append((i >> b & 1) == 1 ? "BB" : "Aa");
      json.append("\":").append(i + 1);
    }
    ObjectWalk walk = KeyProof.walk(new RunState(), object(json.append('}').toString()), false);
    assertThat(walk.size()).as("size of the escaped key and 2^15 colliding keys").isEqualTo(n + 1);
    assertThat(walk.plan()).as("plan of distinct keys after the sort").isNull();
    assertThat(walk.key(walk.first()).value()).as("the escaped key, decoded").isEqualTo("ÿ".repeat(ESCAPES));
  }

  @Test
  void aWalkReadsKeysAndFieldsAsItsObjectDoes() {
    // A walk is Keyed: its field read is its object's, one tick per key scanned; its key is the key at its cursor.
    ObjectView view = object("{\"a\":1,\"b\":2,\"a\":3}");
    ObjectWalk walk = KeyProof.walk(new RunState(), view, false);
    RunState state = new RunState();
    JqValue a = walk.get(state, "a");
    assertThat(a.toJson()).as("the walk's .a").isEqualTo("3");
    assertThat(state.steps()).as("ticks of the walk's .a").isEqualTo(3);
    assertThat(walk.key(walk.first()).value()).as("the walk's first key").isEqualTo("a");
    assertThat(view.key(view.index() + 3).value()).as("the view's key at tape index 3").isEqualTo("b");
  }
}
