package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * The steps an operator's native loop charges (ADR-0008: every loop whose length depends on the data ticks per element
 * or per KiB), at the public API. Each rate is pinned by two counts: the step boundary (the least step budget under
 * which the run completes, found by bisection over fresh engines as StepBudgetTest cuts) of two inputs that differ only
 * in what the rate counts, so the sites the inputs share cancel out and the difference is the rate times the change.
 * The sites already built are known: a field on a document object scans every key (one tick each, ADR-0034), .[] ticks
 * once per element, a collect none; documents are read on another engine's Workspace, so a run has no read tick
 * (ADR-0015). The rates: a string concat one tick per started KiB of its result; an array difference one per pair
 * compared, stopping at the first equal element as jq's binop_minus does; a split one per piece; an object + or a merge
 * one per entry of the right operand, at every merge level.
 */
class ArithmeticStepsTest {
  private static final long CEILING = 1 << 16;

  private static final JqEngine READER = JqEngine.builder().build();

  @Test
  void aConcatChargesOneStepPerStartedKibibyteOfItsResult() {
    // "x" + 1022, 1023 and 1024 letters: results of 1023, 1024 and 1025 bytes, one, one and two KiB started.
    long r1023 = boundary(".a + .b", concat(1022));
    long r1024 = boundary(".a + .b", concat(1023));
    long r1025 = boundary(".a + .b", concat(1024));
    assertThat(r1024 - r1023).as("a 1024-byte result against a 1023-byte one").isEqualTo(0);
    assertThat(r1025 - r1024).as("a 1025-byte result against a 1024-byte one").isEqualTo(1);
  }

  @Test
  void aDifferenceChargesOneStepPerPairCompared() {
    // [.a[]] - [.b[]]: 2 x 3 disjoint numbers compare 6 pairs, 4 x 5 compare 20; collecting them ticks once per element,
    // 5 and 9. So the boundaries differ by 14 + 4.
    long twoByThree = boundary("[.a[]] - [.b[]]", document("{\"a\":[1,2],\"b\":[3,4,5]}"));
    long fourByFive = boundary("[.a[]] - [.b[]]", document("{\"a\":[1,2,3,4],\"b\":[5,6,7,8,9]}"));
    assertThat(fourByFive - twoByThree).as("4 x 5 pairs against 2 x 3").isEqualTo(18);
    // [1,2] - [1,2,3]: 1 meets 1 at once, 2 meets 1 then 2: 3 pairs, against 6 for [1,2] - [3,4,5].
    long matching = boundary("[.a[]] - [.b[]]", document("{\"a\":[1,2],\"b\":[1,2,3]}"));
    assertThat(twoByThree - matching).as("6 pairs against 3 that stop at the first equal element").isEqualTo(3);
  }

  @Test
  void aSplitChargesOneStepPerPiece() {
    long three = boundary(".s / .d", document("{\"s\":\"a,b,c\",\"d\":\",\"}"));
    long four = boundary(".s / .d", document("{\"s\":\"a,b,c,d\",\"d\":\",\"}"));
    assertThat(four - three).as("four pieces against three").isEqualTo(1);
  }

  @Test
  void anObjectPlusChargesOneStepPerEntryOfItsRightOperand() {
    // The second filter looks .q up once more (2 ticks over the two keys) and gives + one more right entry (1).
    JqValue pq = document("{\"p\":1,\"q\":2}");
    long one = boundary("{x:.p} + {y:.q}", pq);
    long two = boundary("{x:.p} + {y:.q,z:.q}", pq);
    assertThat(two - one).as("two right entries against one").isEqualTo(3);
  }

  @Test
  void aMergeChargesOneStepPerEntryAtEveryLevel() {
    JqValue pq = document("{\"p\":1,\"q\":2}");
    long one = boundary("{x:.p} * {y:.q}", pq);
    long two = boundary("{x:.p} * {y:.q,z:.q}", pq);
    assertThat(two - one).as("two right entries against one").isEqualTo(3);
    // The same lookups, one more level of one entry each side.
    long twoLevels = boundary("{k:{x:.p}} * {k:{y:.q}}", pq);
    long threeLevels = boundary("{k:{k:{x:.p}}} * {k:{k:{y:.q}}}", pq);
    assertThat(threeLevels - twoLevels).as("three merge levels against two").isEqualTo(1);
  }

  /** {"a":"x","b":<letters>}: under 2 KiB whatever the letters. */
  private static JqValue concat(int letters) {
    return document("{\"a\":\"x\",\"b\":\"" + "y".repeat(letters) + "\"}");
  }

  /** The least step budget under which {@code filter} on {@code input} completes. */
  private static long boundary(String filter, JqValue input) {
    assertThat(completes(filter, input, CEILING)).as("%s completes with %d steps", filter, CEILING).isTrue();
    long lo = 0;
    long hi = CEILING;
    while (lo < hi) {
      long mid = (lo + hi) >>> 1;
      if (completes(filter, input, mid)) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  private static boolean completes(String filter, JqValue input, long limit) {
    JqEngine engine = steps(limit);
    try {
      return engine.compile(filter).run(input, NONE, new Results(), engine.newWorkspace());
    } catch (JqTermination t) {
      assertThat(t.budget()).as("budget that ended %s with %d steps", filter, limit).isEqualTo(Budget.STEPS);
      return false;
    }
  }

  private static JqValue document(String json) {
    return read(READER.newWorkspace(), json.getBytes(UTF_8)).root();
  }
}
