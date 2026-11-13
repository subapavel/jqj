package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * The tick sites #63 adds, at the public API (ADR-0008, F-32, ADR-0034, the #63 approval's decision 3): a comma charges
 * one step on entering its right branch; a Many loop ({@code .[]}) one step per element, before its emit, and over an
 * object that is not a distinct-key object one more per key its scans compare; a tape walk
 * charges the entries it steps, once per walk: a field's key scan, {@code .[n]}, a view's length and a view's slice; a
 * built array's slice charges the elements it copies, a string's slice one step per started KiB. A run may take exactly
 * its budget of ticks and the next one ends it with {@code JqTermination(BUDGET, STEPS)} (ADR-0030): each site has an
 * adjacent pair, N steps complete and N - 1 end. A document under 1 KiB reads in one tick, one tick per started KiB
 * above (ADR-0015, StepBudgetTest); a run on a value no read made has no read tick. The sinks neither write nor tick,
 * except the compact writer of the print pairs, one step per started KiB of each drain (F-23).
 *
 * <p>#82 (ADR-0002 as amended by F-40) re-pins the object pairs: the reader tags an object of more than 8 keys, all tag
 * 5, none repeated, as a distinct-key object (tag 10). There {@code .[]} charges only its loop's step per member, a
 * field's scan stops at its match and the writer compares no keys; any other object (tag 9) keeps #63's scans, and the
 * writer's key walk on it is ticked the same way.
 */
class NavigationBudgetTest {

  /** {@code 1, 1, …, 1} with {@code n} ones. */
  private static String ones(int n) {
    StringJoiner chain = new StringJoiner(", ");
    for (int i = 0; i < n; i++) chain.add("1");
    return chain.toString();
  }

  /** {@code [1,1,…,1]} with {@code n} elements. */
  private static String array(int n) {
    StringJoiner array = new StringJoiner(",", "[", "]");
    for (int i = 0; i < n; i++) array.add("1");
    return array.toString();
  }

  @Test
  void commaChainOfFortyEndsAsStepBudget() {
    // Spec #56 story 18 and #63's acceptance: a comma chain of 40 under a small step budget. The chain enters 39 right
    // branches; with 10 steps the 11th entry ends the run, after the 11 ones that came before it. jq-ref 1.8.2 gives the
    // 40 ones (comma/three and the chains of diff/comma.rec: a comma emits its left results, then its right ones).
    JqEngine ten = steps(10);
    Results results = new Results();
    assertBudget(() -> ten.compile(ones(40)).run(JqNull.NULL, NONE, results, ten.newWorkspace()), Budget.STEPS, "a chain of 40 with 10 steps");
    assertThat(results.values).as("results before the 11th right branch").hasSize(11);
  }

  @Test
  void commaChargesOneStepPerRightBranch() {
    // 40 ones enter 39 right branches: 39 steps complete, 38 end the run before the last one.
    JqEngine exact = steps(39);
    Results all = new Results();
    assertThat(exact.compile(ones(40)).run(JqNull.NULL, NONE, all, exact.newWorkspace())).as("run's answer for 40 ones with 39 steps").isTrue();
    assertThat(all.values).as("results of 40 ones with 39 steps").hasSize(40);
    assertThat(all.values.stream().map(JqValue::toJson).distinct().toList()).as("the texts of 40 ones").containsExactly("1");

    JqEngine under = steps(38);
    Results cut = new Results();
    assertBudget(() -> under.compile(ones(40)).run(JqNull.NULL, NONE, cut, under.newWorkspace()), Budget.STEPS, "40 ones with 38 steps");
    assertThat(cut.values).as("results of 40 ones with 38 steps").hasSize(39);

    // A chain of two: one right branch, one step; no step for a lone constant.
    JqEngine one = steps(1);
    Results two = new Results();
    assertThat(one.compile("1, 2").run(JqNull.NULL, NONE, two, one.newWorkspace())).as("run's answer for 1, 2 with 1 step").isTrue();
    assertThat(two.values).as("results of 1, 2").hasSize(2);
    JqEngine zero = steps(0);
    Results first = new Results();
    assertBudget(() -> zero.compile("1, 2").run(JqNull.NULL, NONE, first, zero.newWorkspace()), Budget.STEPS, "1, 2 with 0 steps");
    assertThat(first.values).as("results of 1, 2 with 0 steps: the left one only").hasSize(1);
  }

  @Test
  void iterateChargesOneStepPerElement() {
    // [1,…,1] with 20 elements: 41 bytes, one read tick, then one tick per element before its emit (ADR-0034).
    String twenty = array(20);
    JqEngine exact = steps(21);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, twenty).root();
    Results all = new Results();
    assertThat(exact.compile(".[]").run(root, NONE, all, whole)).as("run's answer for .[] over 20 elements with 21 steps").isTrue();
    assertThat(all.values).as("results of .[] over 20 elements").hasSize(20);

    JqEngine under = steps(20);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, twenty).root();
    Results some = new Results();
    assertBudget(() -> under.compile(".[]").run(cutRoot, NONE, some, cut), Budget.STEPS, ".[] over 20 elements with 20 steps");
    // The 20th element's tick comes before its emit.
    assertThat(some.values).as("results of .[] over 20 elements with 20 steps").hasSize(19);
  }

  /** {@code {"k0":b,…,"k<n-1>":b+n-1}}: n distinct keys, all tag 5, the value of k<i> being b + i; under 1 KiB here. */
  private static String keys(int n, int b) {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = 0; i < n; i++) object.add("\"k" + i + "\":" + (b + i));
    return object.toString();
  }

  /** {@code {"k0":0,…,"k<n-1>":n-1}}. */
  private static String keys(int n) {
    return keys(n, 0);
  }

  /** The texts {@code 0} to {@code n - 1}. */
  private static String[] texts(int n) {
    String[] texts = new String[n];
    for (int i = 0; i < n; i++) texts[i] = Integer.toString(i);
    return texts;
  }

  /** {@code {"k0":0,…,"k19":19}}: 20 distinct keys, a distinct-key object (#82). */
  private static final String TWENTY_KEYS = keys(20);

  /** {@link #TWENTY_KEYS} and then {@code "k0":20}: 21 keys, 20 of them distinct, so not proved (tag 9). */
  private static final String REPEATED = TWENTY_KEYS.substring(0, TWENTY_KEYS.length() - 1) + ",\"k0\":20}";

  @Test
  void fieldOnADistinctObjectStopsAtItsMatch() {
    // ADR-0002 as amended for #82: on a distinct-key object a field's key scan stops at its match, one tick per key
    // scanned (ADR-0034). .k0 scans 1 key, 2 with the read; .k19 scans all 20, 21; .zz matches none of the 20, 21. On
    // REPEATED (tag 9) the last k0 wins, so .k0 scans all 21 keys: 22. This replaces #63's fieldChargesForTheKeysItScans,
    // whose .k0 took 21 on TWENTY_KEYS (every key, since the last one wins): the rate change is #82's purpose; its .zz
    // pair is kept unchanged. jq-ref 1.8.2, observed 2026-11-13: .k0, .k19, .zz on TWENTY_KEYS give 0, 19 and null, and
    // .k0 on REPEATED gives 20.
    assertExactSteps(".k0", TWENTY_KEYS, 2, "0");
    assertExactSteps(".k19", TWENTY_KEYS, 21, "19");
    assertExactSteps(".zz", TWENTY_KEYS, 21, "null");
    assertExactSteps(".k0", REPEATED, 22, "20");
  }

  @Test
  void indexChargesForTheEntriesItSteps() {
    // ADR-0034: .[n] charges the entries it steps. Past the end it steps all 20: 1 read tick and 20 walk ticks; jq-ref
    // gives null past the end (index/key-3).
    String twenty = array(20);
    JqEngine exact = steps(21);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, twenty).root();
    Results past = new Results();
    assertThat(exact.compile(".[20]").run(root, NONE, past, whole)).as("run's answer for .[20] over 20 elements with 21 steps").isTrue();
    assertThat(past.values).as("results of .[20] over 20 elements").containsExactly(JqNull.NULL);
    JqEngine under = steps(20);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, twenty).root();
    assertBudget(() -> under.compile(".[20]").run(cutRoot, NONE, new Results(), cut), Budget.STEPS, ".[20] over 20 elements with 20 steps");

    // .[19] steps over the 19 elements before its own: 1 read tick and 19 walk ticks (ArrayView.get, one tick per element
    // stepped over).
    assertExactSteps(".[19]", twenty, 20, "1");
  }

  /**
   * The adjacent pair at the public API: {@code filter} on the document {@code json} completes with {@code steps} steps,
   * giving {@code texts}, and ends with {@code JqTermination(BUDGET, STEPS)} with {@code steps - 1}.
   */
  private static void assertExactSteps(String filter, String json, long steps, String... texts) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    Results all = new Results();
    assertThat(exact.compile(filter).run(root, NONE, all, whole)).as("run's answer for %s with %s steps", filter, steps).isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results of %s with %s steps", filter, steps).containsExactly(texts);
    JqEngine under = steps(steps - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(() -> under.compile(filter).run(cutRoot, NONE, new Results(), cut), Budget.STEPS, filter + " with " + (steps - 1) + " steps");
  }

  /** {@code [0,1,…,19]}, under 1 KiB. */
  private static final String COUNTING = counting();

  private static String counting() {
    StringJoiner array = new StringJoiner(",", "[", "]");
    for (int i = 0; i < 20; i++) array.add(Integer.toString(i));
    return array.toString();
  }

  @Test
  void aNegativeIndexChargesTheLengthOfAView() {
    // A negative index counts from the end, so a view's length is walked first: one tick per element (ArrayView.length),
    // then the walk to the element. The key comes from the document (a negative literal in the filter is a Stub):
    // [-1,1,…,19] read 1, .[0] steps 0, the length 20, the walk to index 19 steps 19: 40. jq-ref 1.8.2, observed
    // 2026-11-12: jq-ref -nc '[-1, range(1;20)] | .[.[0]]' gives 19.
    String array = COUNTING.replaceFirst("\\[0,", "[-1,");
    assertExactSteps(".[.[0]]", array, 40, "19");
  }

  @Test
  void aSliceOfAViewChargesTheLengthAndTheElementsUpToItsEnd() {
    // Slice.of walks the length (20), then ArrayView.slice charges its end, the walk to the start and the copy (5):
    // read 1 + 20 + 5 = 26. jq-ref 1.8.2, observed 2026-11-12: [range(20)] | .[2:5] gives [2,3,4].
    assertExactSteps(".[2:5]", COUNTING, 26, "[2,3,4]");
  }

  @Test
  void aSliceOfABuiltArrayChargesOneStepPerElementCopied() {
    // .[1:] on the view: 20 + 20; then .[1:] on the 19 elements it built: no walk for the length, and 18 copied
    // (BuiltArray.slice): read 1 + 40 + 18 = 59. jq-ref 1.8.2, observed 2026-11-12: [range(20)] | .[1:] | .[1:] gives
    // [2,…,19].
    StringJoiner rest = new StringJoiner(",", "[", "]");
    for (int i = 2; i < 20; i++) rest.add(Integer.toString(i));
    assertExactSteps(".[1:] | .[1:]", COUNTING, 59, rest.toString());
  }

  @Test
  void aSliceOfAStringChargesOneStepPerStartedKiB() {
    // Slice.string counts the code points in one pass over the bytes: one tick per started KiB of them, the writer's
    // rate. The document's read is one tick per started KiB too (ADR-0015): "a"×1024 is a 1026-byte document, 2 read
    // ticks, and 1 slice tick; "a"×1025 and "é"×513 (1026 bytes) take 2 slice ticks, so bytes count, not code points;
    // "abc" takes 1. jq-ref 1.8.2, observed 2026-11-12: "a" * 1024 | .[1:3] and "a" * 1025 | .[1:3] give "aa",
    // "é" * 513 | .[1:3] gives "éé".
    assertExactSteps(".[1:3]", "\"" + "a".repeat(1024) + "\"", 3, "\"aa\"");
    assertExactSteps(".[1:3]", "\"" + "a".repeat(1025) + "\"", 4, "\"aa\"");
    assertExactSteps(".[1:3]", "\"" + "é".repeat(513) + "\"", 4, "\"éé\"");
    assertExactSteps(".[1:3]", "\"abc\"", 2, "\"bc\"");
  }

  @Test
  void iterateOverADistinctObjectChargesOneStepPerMember() {
    // ADR-0002 as amended for #82 (F-40): the reader proves TWENTY_KEYS a distinct-key object (more than 8 keys, all tag
    // 5, none repeated), so .[] takes each member once and charges only its loop's tick per member (ADR-0034): 1 read
    // tick and 20, so 21 complete and 20 end before the 20th emit. This replaces the 20-key pair of #63's
    // iterateOverAnObjectChargesEachKeyComparison, 420 and 419 (n * n + n - 1 ticks and the read): the rate change is
    // #82's purpose. jq-ref 1.8.2, observed 2026-11-13: [.[]] over TWENTY_KEYS gives [0,1,…,19].
    assertExactSteps(".[]", TWENTY_KEYS, 21, texts(20));
  }

  @Test
  void iterateOverAnObjectWithARepeatedKeyKeepsTheScanRate() {
    // A repeated key leaves the object unproved (tag 9), so .[] keeps #63's scans, one tick per key compared (ObjectView
    // on 5ab4aa7): k0's last value compares the 20 later keys; each of k1…k19 (at i) compares i + 1 keys for its first
    // position and 20 - i for its last value, 21; the second k0 compares 1 and is skipped. 20 + 19 * 21 + 1 = 420 scan
    // ticks, 20 loop ticks and the read: 441 complete and 440 end, #63's pair unchanged. jq-ref 1.8.2, observed
    // 2026-11-13: [.[]] over REPEATED gives [20,1,…,19].
    String[] values = texts(20);
    values[0] = "20";
    assertExactSteps(".[]", REPEATED, 441, values);
  }

  @Test
  void iterateOverEightKeysKeepsTheScanRateAndNineDoNot() {
    // Only an object of more than 8 keys is proved (ADR-0002 as amended; O2 of #82's approval). 8 distinct keys stay tag
    // 9 and keep #63's n * n + n - 1 = 71 ticks, with the read 72 and 71; 9 distinct keys are tag 10, 9 loop ticks and
    // the read, 10 and 9. jq-ref 1.8.2, observed 2026-11-13: [.[]] gives [0,…,7] and [0,…,8].
    assertExactSteps(".[]", keys(8), 72, texts(8));
    assertExactSteps(".[]", keys(9), 10, texts(9));
  }

  @Test
  void iterateWithAnEscapedKeyKeepsTheScanRate() {
    // The proof takes only objects whose keys are all tag 5: an escape sequence makes a key tag 6 (ADR-0015), so
    // k0…k18 and "\u006b20" (k20) stay tag 9 although no key repeats. 20 keys: 419 scan and loop ticks and the read, 420
    // and 419. jq-ref 1.8.2, observed 2026-11-13: [.[]] gives [0,…,18,20], and keys_unsorted ends with "k20".
    String nineteen = keys(19);
    String escaped = nineteen.substring(0, nineteen.length() - 1) + ",\"\\u006b20\":20}";
    String[] values = Arrays.copyOf(texts(19), 20);
    values[19] = "20";
    assertExactSteps(".[]", escaped, 420, values);
  }

  /**
   * The writer's pair at the public API: {@code .} on the document {@code json}, printed by the compact writer, completes
   * with {@code steps} steps and prints {@code printed}, and ends with {@code JqTermination(BUDGET, STEPS)} with
   * {@code steps - 1}.
   */
  private static void assertPrintSteps(String json, long steps, String printed) {
    JqEngine exact = steps(steps);
    Workspace whole = exact.newWorkspace();
    JqValue root = read(whole, json).root();
    RecordingTarget target = new RecordingTarget();
    assertThat(exact.compile(".").run(root, NONE, writer(whole, target), whole)).as("run's answer printing %s with %s steps", json, steps).isTrue();
    assertThat(target.text()).as("output printing %s with %s steps", json, steps).isEqualTo(printed);
    JqEngine under = steps(steps - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, json).root();
    assertBudget(
        () -> under.compile(".").run(cutRoot, NONE, writer(cut, new RecordingTarget()), cut), Budget.STEPS, "printing " + json + " with " + (steps - 1) + " steps");
  }

  @Test
  void printingAnObjectWithARepeatedKeyChargesItsKeyComparisons() {
    // ADR-0002 as amended for #82 (F-40): the writer's key walk on a tag-9 object, untimed before #82, is ticked as .[]'s
    // scans are: the 420 comparisons of iterateOverAnObjectWithARepeatedKeyKeepsTheScanRate (the first key's own first
    // position needs none, as ObjectView.first), the read tick and one tick for the drain of under 1 KiB (F-23): 422
    // complete and 421 end. jq-ref 1.8.2, observed 2026-11-13: -c . on REPEATED prints {"k0":20,"k1":1,…,"k19":19}.
    assertPrintSteps(REPEATED, 422, TWENTY_KEYS.replaceFirst("\"k0\":0,", "\"k0\":20,") + "\n");
  }

  @Test
  void printingADistinctObjectChargesNoKeyComparison() {
    // On a distinct-key object the writer takes each member once and compares no key: the read tick and the drain's, 2
    // complete and 1 ends. With the comparisons of a scan it would take 401 (19 + 19 * 20 and the two). jq-ref 1.8.2,
    // observed 2026-11-13: -c . on TWENTY_KEYS prints it unchanged.
    assertPrintSteps(TWENTY_KEYS, 2, TWENTY_KEYS + "\n");
  }

  @Test
  void iterateAfterAReadInTheSinkIsAStaleView() {
    // ADR-0026: an iteration that calls host code checks its document again after every call; the sink reads another
    // document into the same Workspace on its first call, so the next step must be STALE_VIEW, never an element of the
    // later document. The later calls touch no view, so only the iteration's own step can throw. jq-ref 1.8.2:
    // iterate/plain-array and nested-values give each element in order.
    for (String[] c : new String[][] {{"[1,2,3]", "[7,8,9]"}, {"{\"a\":1,\"b\":2,\"c\":3}", "{\"x\":7,\"y\":8,\"z\":9}"}}) {
      Workspace workspace = JqEngine.builder().build().newWorkspace();
      JqValue root = read(workspace, c[0]).root();
      List<String> calls = new ArrayList<>();
      List<JsonDoc> readInside = new ArrayList<>();
      JqSink reading =
          v -> {
            if (readInside.isEmpty()) {
              calls.add(v.toJson());
              readInside.add(read(workspace, c[1]));
            } else {
              calls.add("a later call");
            }
            return true;
          };
      assertStale(() -> JqEngine.builder().build().compile(".[]").run(root, NONE, reading, workspace), ".[] over " + c[0] + " after its sink read " + c[1]);
      assertThat(calls).as("sink calls for .[] over %s: the first element only", c[0]).containsExactly("1");
      // Witness: the document read inside the sink is current and reads.
      JqValue later = readInside.getFirst().root();
      if (later instanceof JqArray array) {
        assertThat(kind(JqNumber.class, array.get(1), "[7,8,9][1]").longValue()).as("[7,8,9][1]").isEqualTo(8L);
      } else {
        assertThat(kind(JqNumber.class, kind(JqObject.class, later, "{x,y,z}").get("y"), ".y").longValue()).as(".y of {x,y,z}").isEqualTo(8L);
      }
    }
  }

  @Test
  void iterateOverADistinctObjectAfterAReadInTheSinkIsAStaleView() {
    // The wide variant of iterateAfterAReadInTheSinkIsAStaleView: the distinct walk takes each member once, and its next
    // step after the sink's read must still be STALE_VIEW (ADR-0026), never a member of the later document, which has
    // the same 20 keys on the same reused tape. Under 10 steps: the distinct walk reaches its first emit with 2 (the read
    // and one loop tick) and the sink's read is the run's, 1 more (ADR-0030); the scan walk needs 21 before that emit
    // (19 for k0's last value), so the sink is reached only on the distinct walk. The later calls touch no view.
    JqEngine engine = steps(10);
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, TWENTY_KEYS).root();
    String laterJson = keys(20, 100);
    List<String> calls = new ArrayList<>();
    List<JsonDoc> readInside = new ArrayList<>();
    JqSink reading =
        v -> {
          if (readInside.isEmpty()) {
            calls.add(v.toJson());
            readInside.add(read(workspace, laterJson));
          } else {
            calls.add("a later call");
          }
          return true;
        };
    assertStale(() -> engine.compile(".[]").run(root, NONE, reading, workspace), ".[] over TWENTY_KEYS after its sink read " + laterJson);
    assertThat(calls).as("sink calls for .[] over TWENTY_KEYS: the first member only").containsExactly("0");
    // Witness: the document read inside the sink is current and reads.
    JqValue later = readInside.getFirst().root();
    assertThat(kind(JqNumber.class, kind(JqObject.class, later, "the later document").get("k1"), ".k1").longValue()).as(".k1 of the later document").isEqualTo(101L);
  }
}
