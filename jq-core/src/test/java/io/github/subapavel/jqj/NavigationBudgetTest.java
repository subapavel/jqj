package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.steps;
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
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * The tick sites #63 adds, at the public API (ADR-0008, F-32, ADR-0034, the #63 approval's decision 3): a comma charges
 * one step on entering its right branch; a Many loop ({@code .[]}) one step per element, before its emit; a tape walk
 * charges the entries it steps, once per walk: a field's key scan and {@code .[n]}. A run may take exactly its budget of
 * ticks and the next one ends it with {@code JqTermination(BUDGET, STEPS)} (ADR-0030). Each document here is under 1 KiB,
 * so its read is one tick (ADR-0015, StepBudgetTest); a run on a value no read made has no read tick. The sinks neither
 * write nor tick.
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

  /** {@code {"k0":0,…,"k19":19}}: 20 keys, under 1 KiB. */
  private static final String TWENTY_KEYS = twentyKeys();

  private static String twentyKeys() {
    StringJoiner object = new StringJoiner(",", "{", "}");
    for (int i = 0; i < 20; i++) object.add("\"k" + i + "\":" + i);
    return object.toString();
  }

  @Test
  void fieldChargesForTheKeysItScans() {
    // Decision 3 of the #63 approval: a field's key scan charges tick(keys). The last value of a key wins (ADR-0002), so
    // the scan reads all 20 keys for the first key as for a missing one: 1 read tick and 20 scan ticks.
    for (String[] c : new String[][] {{".k0", "0"}, {".zz", "null"}}) {
      JqEngine exact = steps(21);
      Workspace whole = exact.newWorkspace();
      JqValue root = read(whole, TWENTY_KEYS).root();
      Results results = new Results();
      assertThat(exact.compile(c[0]).run(root, NONE, results, whole)).as("run's answer for %s over 20 keys with 21 steps", c[0]).isTrue();
      assertThat(results.values).as("results of %s over 20 keys", c[0]).hasSize(1);
      assertThat(results.values.getFirst().toJson()).as("the result of %s over 20 keys", c[0]).isEqualTo(c[1]);

      JqEngine under = steps(20);
      Workspace cut = under.newWorkspace();
      JqValue cutRoot = read(cut, TWENTY_KEYS).root();
      assertBudget(() -> under.compile(c[0]).run(cutRoot, NONE, new Results(), cut), Budget.STEPS, c[0] + " over 20 keys with 20 steps");
    }
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

    // .[19] steps 19 entries to its element (20 if the element itself counts): 1 + 18 steps end it, 1 + 20 complete it.
    JqEngine short18 = steps(19);
    Workspace few = short18.newWorkspace();
    JqValue fewRoot = read(few, twenty).root();
    assertBudget(() -> short18.compile(".[19]").run(fewRoot, NONE, new Results(), few), Budget.STEPS, ".[19] over 20 elements with 19 steps");
    JqEngine enough = steps(21);
    Workspace last = enough.newWorkspace();
    JqValue lastRoot = read(last, twenty).root();
    Results one = new Results();
    assertThat(enough.compile(".[19]").run(lastRoot, NONE, one, last)).as("run's answer for .[19] with 21 steps").isTrue();
    assertThat(one.values).as("results of .[19]").hasSize(1);
    assertThat(one.values.getFirst().toJson()).as("the result of .[19]").isEqualTo("1");
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
}
