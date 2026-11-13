package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.internal.value.Tape;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * Below the API, as TapeTest (#22's one exception): the tag the reader writes for an object, ADR-0002 as amended by
 * F-40 for #82. At an object's close the reader proves its keys distinct when it has more than 8 keys, all of them tag
 * 5, and none repeated: tag 10, a distinct-key object. Every other object is tag 9, not proved. The tag is read off the
 * tape of the returned Document ({@code Document.live()}); 10 is ADR-0002's tag table, which no constant names yet.
 *
 * <p>{@link #aProbeChainPastEightLeavesTagNine} assumes one package-private method that #82 adds to the reader,
 * {@code static int JsonReader.keyHash(byte[] bytes, long entry)}: the hash the proof gives the tag-5 key whose tape
 * entry over {@code bytes} is {@code entry}, a function of the key's bytes alone, wherever they lie. A key's home slot
 * is {@code keyHash & (capacity - 1)} for the table's power-of-two capacity, at least 16 and at most 2^20 slots; the
 * keys go in in source order, each probing from its home slot in an order that slot fixes (linear probing, say); a
 * key's probes are the slots it reads, its home slot first, and a key that needs more than 8 ends the proof (tag 9).
 */
class JsonReaderTest {

  /** ADR-0002: an object not proved distinct. */
  private static final int OBJECT = Tape.OBJECT;

  /** ADR-0002 as amended by F-40: a distinct-key object. */
  private static final int DISTINCT = 10;

  private static MemorySegment segment(String json) {
    return MemorySegment.ofArray(json.getBytes(UTF_8));
  }

  /** Asserts that reading {@code json} with {@code reader} writes {@code tag} at tape index {@code index}. */
  private static void assertTag(JsonReader reader, String json, int index, int tag, String what) {
    long[] tape = reader.read(segment(json)).live();
    assertThat(Tape.tag(tape[index])).as("tag of %s (tape index %d) in %s", what, index, json).isEqualTo(tag);
  }

  /** {@code "<p><from>":<from>,…,"<p><to-1>":<to-1>}: the members of distinct tag-5 keys, without braces. */
  private static String members(String p, int from, int to) {
    StringJoiner members = new StringJoiner(",");
    for (int i = from; i < to; i++) members.add("\"" + p + i + "\":" + i);
    return members.toString();
  }

  /** {@code {"k0":0,…,"k<n-1>":n-1}}. */
  private static String keys(int n) {
    return "{" + members("k", 0, n) + "}";
  }

  @Test
  void aWideObjectOfDistinctKeysIsTagTen() {
    JsonReader reader = new JsonReader(TapeLimits.ADR_0002);
    // More than 8 keys, all tag 5, none repeated (ADR-0002 as amended): 9, the first count proved, and 20.
    assertTag(reader, keys(9), 0, DISTINCT, "9 distinct keys");
    assertTag(reader, keys(20), 0, DISTINCT, "20 distinct keys");
    // The same document again on the same reader: its reused table keeps nothing of the last read's keys.
    assertTag(reader, keys(20), 0, DISTINCT, "20 distinct keys read again");
    // Keys that share their bytes but one, their length, or a prefix; the empty key; keys of well-formed non-ASCII
    // UTF-8, which are tag 5 (ADR-0015).
    assertTag(reader, "{\"\":0,\"a\":1,\"aa\":2,\"ab\":3,\"ba\":4,\"b\":5,\"é\":6,\"€\":7,\"𝄞\":8,\"aaa\":9}", 0, DISTINCT, "10 short distinct keys");
    // Only an object of more than 8 keys is proved (O2 of #82's approval): 8, 1 and none stay tag 9.
    assertTag(reader, keys(8), 0, OBJECT, "8 distinct keys");
    assertTag(reader, keys(1), 0, OBJECT, "one key");
    assertTag(reader, "{}", 0, OBJECT, "the empty object");
    // A wide object as an array's second element is proved at its own close too.
    assertTag(reader, "[1," + keys(9) + "]", 2, DISTINCT, "9 distinct keys after an array's first element");
  }

  @Test
  void aRepeatedKeyOrATagSixKeyLeavesTagNine() {
    JsonReader reader = new JsonReader(TapeLimits.ADR_0002);
    String nine = members("k", 0, 9);
    // Each object below is a twin of a tag-10 one: keys(9) and keys(10), proved in aWideObjectOfDistinctKeysIsTagTen.
    assertTag(reader, "{" + nine + ",\"k0\":9}", 0, OBJECT, "9 keys and the first repeated last");
    assertTag(reader, "{" + nine + ",\"k4\":9}", 0, OBJECT, "9 keys and the middle one repeated last");
    assertTag(reader, "{" + nine + ",\"k8\":9}", 0, OBJECT, "9 keys and the last one repeated next to it");
    assertTag(reader, "{\"k0\":0,\"k0\":1," + members("k", 1, 9) + "}", 0, OBJECT, "a repeat next to the first key");
    assertTag(reader, "{" + members("k", 0, 8) + ",\"k0\":8}", 0, OBJECT, "9 keys of which 8 distinct");
    // A key equal to another by its decoded bytes only (#35): "\u0061" is a. Its twin without the escape, c, is tag 10.
    String ten = members("k", 1, 9);
    assertTag(reader, "{\"a\":0," + ten + ",\"c\":9}", 0, DISTINCT, "10 distinct keys a, k1…k8, c");
    assertTag(reader, "{\"a\":0," + ten + ",\"\\u0061\":9}", 0, OBJECT, "10 keys whose last is an escape of the first");
    // A tag-6 key and no repeat: an escape sequence, or DEL (ADR-0015); the proof takes only tag-5 keys.
    assertTag(reader, "{\"a\":0," + ten + ",\"\\u0063\":9}", 0, OBJECT, "10 distinct keys whose last is an escape of c");
    assertTag(reader, "{\"a\":0," + ten + ",\"c\u007f\":9}", 0, OBJECT, "10 distinct keys whose last holds DEL");
  }

  @Test
  void nestedObjectsAreProvedApart() {
    JsonReader reader = new JsonReader(TapeLimits.ADR_0002);
    String inner = keys(10);
    String innerRepeat = "{" + members("k", 0, 10) + ",\"k0\":10}";
    // Tape index 2 is the value of the first key, the inner object; k<i>'s value is at 2 + 2i.
    // An inner repeat is the inner object's own: the outer stays tag 10.
    String outer = "{\"o0\":" + innerRepeat + "," + members("o", 1, 10) + "}";
    assertTag(reader, outer, 0, DISTINCT, "a distinct outer object around an inner repeat");
    assertTag(reader, outer, 2, OBJECT, "the inner object with a repeat");
    // The reverse: a repeat in the outer object, after a distinct inner one.
    String repeatAround = "{\"o0\":" + inner + "," + members("o", 1, 10) + ",\"o0\":10}";
    assertTag(reader, repeatAround, 0, OBJECT, "an outer object with a repeat around a distinct one");
    assertTag(reader, repeatAround, 2, DISTINCT, "the distinct inner object");
    // The same key names inside and outside, each set distinct: an inner key is no repeat of the outer ones, first or
    // last in the outer object.
    String sameNames = "{\"k0\":" + inner + "," + members("k", 1, 10) + "}";
    assertTag(reader, sameNames, 0, DISTINCT, "an outer object whose first value holds its own key names");
    assertTag(reader, sameNames, 2, DISTINCT, "the inner object of the outer one's key names");
    String sameNamesLast = "{" + members("k", 0, 9) + ",\"k9\":" + inner + "}";
    assertTag(reader, sameNamesLast, 0, DISTINCT, "an outer object whose last value holds its own key names");
    assertTag(reader, sameNamesLast, 20, DISTINCT, "the inner object as the last value");
    // A narrow inner object (not proved) of the outer one's names, and three levels deep.
    String narrow = "{\"k0\":{\"k1\":1,\"k2\":2}," + members("k", 1, 10) + "}";
    assertTag(reader, narrow, 0, DISTINCT, "a distinct outer object around a narrow one");
    assertTag(reader, narrow, 2, OBJECT, "the narrow inner object");
    String deep = "{\"k0\":{\"k0\":" + innerRepeat + "," + members("k", 1, 10) + "}," + members("k", 1, 10) + "}";
    assertTag(reader, deep, 0, DISTINCT, "the outer of three levels");
    assertTag(reader, deep, 2, DISTINCT, "the middle of three levels");
    assertTag(reader, deep, 4, OBJECT, "the innermost of three levels, with a repeat");
  }

  /** The number of candidate keys {@code "c0"}, {@code "c1"}, … searched for one home slot. */
  private static final int CANDIDATES = 1 << 21;

  /** The low 20 bits of a keyHash: the home slot in any table of up to 2^20 slots. */
  private static final int MASK = (1 << 20) - 1;

  /** The text of candidate key {@code i}, quotes included. */
  private static String candidate(int i) {
    return "\"c" + i + "\"";
  }

  /** {@code JsonReader.keyHash} of candidate {@code i}, its bytes at offset 0 of their own array. */
  private static int hash(int i) {
    byte[] key = candidate(i).getBytes(UTF_8);
    return JsonReader.keyHash(key, Tape.entry(Tape.STRING, key.length, 0));
  }

  @Test
  void aProbeChainPastEightLeavesTagNine() {
    // Count the candidates by the low 20 bits of their hash and take the fullest slot; 2^21 candidates in 2^20 slots
    // fill some slot with 9 for any hash that spreads them.
    int[] counts = new int[MASK + 1];
    for (int i = 0; i < CANDIDATES; i++) counts[hash(i) & MASK]++;
    int slot = 0;
    for (int s = 1; s <= MASK; s++) {
      if (counts[s] > counts[slot]) slot = s;
    }
    assertThat(counts[slot]).as("candidates on the fullest home slot of 2^20").isGreaterThanOrEqualTo(9);
    List<String> sameSlot = new ArrayList<>();
    int other = -1;
    for (int i = 0; i < CANDIDATES && (sameSlot.size() < 9 || other < 0); i++) {
      int h = hash(i);
      if ((h & MASK) == slot) {
        if (sameSlot.size() < 9) sameSlot.add(candidate(i));
      } else if (other < 0 && ((h - slot) & 15) == 9) {
        // 9 slots above the shared one in every table of 16 slots or more, so never on its chain of 8.
        other = i;
      }
    }
    assertThat(other).as("a candidate 9 slots above the shared home slot").isNotNegative();
    JsonReader reader = new JsonReader(TapeLimits.ADR_0002);
    // 9 keys on one home slot: the ninth reads the 8 slots its predecessors hold and a ninth, more than 8 probes.
    StringJoiner nine = new StringJoiner(",", "{", "}");
    for (int k = 0; k < 9; k++) nine.add(sameSlot.get(k) + ":" + k);
    assertTag(reader, nine.toString(), 0, OBJECT, "9 distinct keys on one home slot");
    // Its twin, read next on the same reader: 8 of those keys and one off their chain need at most 8 probes. The proof
    // that ended above leaves nothing in the reused table that would make these 8 keys repeats.
    StringJoiner eight = new StringJoiner(",", "{", "}");
    for (int k = 0; k < 8; k++) eight.add(sameSlot.get(k) + ":" + k);
    eight.add(candidate(other) + ":8");
    assertTag(reader, eight.toString(), 0, DISTINCT, "8 distinct keys on one home slot and one off their chain");
  }
}
