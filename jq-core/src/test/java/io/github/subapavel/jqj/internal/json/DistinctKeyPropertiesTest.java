package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.internal.value.Tape;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.StringJoiner;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #82's two properties, one case per seed (docs/agents/environment.md: seeded loops, no jqwik), below the API as
 * JsonReaderTest. The reader writes tag 10 for an object of more than 8 keys, all tag 5, none repeated, and tag 9 for
 * every other (ADR-0002 as amended by F-40); and with an unproved tag every walker stays correct, only slower, so the
 * walks of a distinct-key object equal those of the same tape re-tagged 9, which takes #63's scans. The oracle of the
 * first is ADR-0002's rule applied to what the seed drew (which names, which spelt with escapes), never the reader's
 * hash; the oracle of the second is the scan walk of 5ab4aa7, which #63's differential cases cover.
 */
class DistinctKeyPropertiesTest {

  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /** The 12 key names a key draws from: the empty key, shared prefixes and lengths, non-ASCII UTF-8 (tag 5 raw). */
  private static final String[] NAMES = {"", "a", "b", "aa", "ab", "ba", "k1", "k10", "key", "é", "€x", "𝄞"};

  /** The JSON text of {@code name}: raw, or with every char as a backslash-u escape (a pair for U+1D11E). */
  private static String spell(String name, boolean escaped) {
    if (!escaped) {
      return "\"" + name + "\"";
    }
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < name.length(); i++) out.append(String.format("\\u%04x", (int) name.charAt(i)));
    return out.append('"').toString();
  }

  /** A random member value: scalars, a tag-6 string, containers, a small object with a repeat. */
  private static String value(SplittableRandom r) {
    return switch (r.nextInt(7)) {
      case 0 -> "null";
      case 1 -> Integer.toString(r.nextInt(-5, 100));
      case 2 -> "\"s\\n\"";
      case 3 -> "[1,[2],{\"a\":3}]";
      case 4 -> "{\"a\":1,\"a\":2}";
      case 5 -> "{}";
      default -> "\"v\"";
    };
  }

  private static MemorySegment segment(String json) {
    return MemorySegment.ofArray(json.getBytes(UTF_8));
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void tagTenOnlyWhenNoKeyRepeats(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    int n = r.nextInt(41);
    List<String> names = new ArrayList<>();
    if (r.nextBoolean()) {
      // Distinct: a shuffle of the names, at most 12 of them.
      List<String> shuffled = new ArrayList<>(Arrays.asList(NAMES));
      for (int i = shuffled.size() - 1; i > 0; i--) shuffled.set(i, shuffled.set(r.nextInt(i + 1), shuffled.get(i)));
      names.addAll(shuffled.subList(0, Math.min(n, NAMES.length)));
    } else {
      for (int i = 0; i < n; i++) names.add(NAMES[r.nextInt(NAMES.length)]);
    }
    // Some objects spell some of their keys with escapes (tag 6); the empty key has nothing to escape.
    boolean mayEscape = r.nextBoolean();
    boolean escaped = false;
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (String name : names) {
      boolean escape = mayEscape && !name.isEmpty() && r.nextInt(4) == 0;
      escaped |= escape;
      object.add(spell(name, escape) + ":" + value(r));
    }
    boolean repeats = new HashSet<>(names).size() < names.size();
    int expected = names.size() > 8 && !escaped && !repeats ? 10 : Tape.OBJECT;
    String json = object.toString();
    long[] tape = new JsonReader(TapeLimits.ADR_0002).read(segment(json)).live();
    assertThat(Tape.tag(tape[0]))
        .as("tag of %s: %d keys, %s, %s", json, names.size(), repeats ? "a repeat" : "no repeat", escaped ? "an escape" : "no escape")
        .isEqualTo(expected);
  }

  /** The characters a distinct key draws from, all tag 5 raw. */
  private static final String[] KEY_CHARS = {"a", "b", "Z", "0", "_", "é", "€", "𝄞"};

  /**
   * A distinct-key object of 9 to 40 keys, each of up to 5 random characters and all different; its values may nest a
   * wide distinct object, a wide object with a repeat or a narrow one, up to {@code depth} levels below.
   */
  private static String wide(SplittableRandom r, int depth, List<String> keysOut) {
    Set<String> keys = new LinkedHashSet<>();
    int n = r.nextInt(9, 41);
    while (keys.size() < n) {
      StringBuilder key = new StringBuilder();
      for (int i = r.nextInt(6); i > 0; i--) key.append(KEY_CHARS[r.nextInt(KEY_CHARS.length)]);
      keys.add(key.toString());
    }
    keysOut.addAll(keys);
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (String key : keys) object.add("\"" + key + "\":" + member(r, depth));
    return object.toString();
  }

  /** A member value: a scalar, a container, or below {@code depth} > 0 a wide object, distinct or with a repeat. */
  private static String member(SplittableRandom r, int depth) {
    int kind = r.nextInt(depth > 0 ? 9 : 7);
    if (kind < 7) {
      return value(r);
    }
    String inner = wide(r, depth - 1, new ArrayList<>());
    // A repeat of the inner object's first key, last: the inner object is then tag 9 on both tapes.
    return kind == 7 ? inner : inner.substring(0, inner.length() - 1) + "," + inner.substring(1, inner.indexOf(':')) + ":\"again\"}";
  }

  /** The members of {@code o} as {@code .[]} walks them: first, then next, each member's compact text. */
  private static List<String> walk(ObjectView o) {
    RunState state = new RunState();
    List<String> members = new ArrayList<>();
    for (int c = o.first(); c != -1; c = o.next(state, c)) members.add(o.member(state, c).toJson());
    return members;
  }

  /** What {@code o.forEach} gives: each key and its value's compact text. */
  private static List<String> forEach(ObjectView o) {
    List<String> calls = new ArrayList<>();
    o.forEach((k, v) -> calls.add(k + " -> " + v.toJson()));
    return calls;
  }

  /** The writer's line for {@code v}, pretty or compact. */
  private static String line(JqValue v, boolean pretty) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Printer().line(v, pretty, (buffer, length) -> out.write(buffer, 0, length));
    return out.toString(UTF_8);
  }

  /** A field's text: its value's compact text, or "absent" for Java null. */
  private static String text(JqValue v) {
    return v == null ? "absent" : v.toJson();
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aDistinctWalkEqualsTheScanWalk(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    List<String> keys = new ArrayList<>();
    String json = wide(r, 2, keys);
    Document proved = new JsonReader(TapeLimits.ADR_0002).read(segment(json));
    long[] tape = proved.live();
    assertThat(Tape.tag(tape[0])).as("tag of the root of %s", json).isEqualTo(10);
    // The copy: the same entries, every tag 10 written as 9 (an object not proved), so its walks take #63's scans.
    long[] scanTape = Arrays.copyOf(tape, Tape.end(tape, 0));
    for (int i = 0; i < scanTape.length; i++) {
      if (Tape.tag(scanTape[i]) == 10) scanTape[i] = Tape.entry(Tape.OBJECT, Tape.span(scanTape[i]), Tape.off(scanTape[i]));
    }
    ObjectView fast = new ObjectView(proved, 0);
    ObjectView scan = new ObjectView(new Document(proved.bytes(), scanTape), 0);

    assertThat(walk(fast)).as("first, next and member on %s", json).isEqualTo(walk(scan));
    assertThat(fast.size()).as("size of %s", json).isEqualTo(scan.size()).isEqualTo(keys.size());
    assertThat(forEach(fast)).as("forEach on %s", json).isEqualTo(forEach(scan));
    RunState state = new RunState();
    List<String> fields = new ArrayList<>(keys);
    // Keys no member has: one, and the last key with a character no key draws.
    fields.add("no such key");
    fields.add(keys.getLast() + "~");
    for (String key : fields) {
      assertThat(text(fast.get(key))).as("get(%s) on %s", key, json).isEqualTo(text(scan.get(key)));
      assertThat(text(fast.get(state, key))).as("get(state, %s) on %s", key, json).isEqualTo(text(scan.get(state, key)));
    }
    assertThat(fast.toJson()).as("toJson of %s", json).isEqualTo(scan.toJson());
    assertThat(line(fast, false)).as("the compact line of %s", json).isEqualTo(line(scan, false));
    assertThat(line(fast, true)).as("the pretty line of %s", json).isEqualTo(line(scan, true));
  }
}
