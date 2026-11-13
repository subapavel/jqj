package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * DEVIATIONS "Huge constant keys" (ADR-0035, #65 standards review): the key-kind check folds a key past the 4 KiB fold
 * cap a second time under 1 MiB of bytes built, so an untrusted filter cannot make the compile allocate gigabytes. jq
 * folds {@code {(("x"*1e9)/"x"):1}} whole at parse time: a 1 GB string, then 10^9 pieces. jqj's second fold stops at
 * the bound before it allocates the string, so the compile is quick (a time witness: the whole fold would take far
 * longer than the limit, or run out of heap), and the key is left to the run, whose own bytes-built budget governs it.
 */
class HugeConstantKeyTest {
  private static final String HUGE = "{((\"x\"*1e9)/\"x\"):1}";

  @Test
  void theCompileOfAHugeConstantKeyBuildsNoGigabyte() {
    JqEngine engine = bytesBuilt(1 << 20);
    JqFilter filter = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> engine.compile(HUGE), "the compile of " + HUGE);
    // Under 1 MiB built the run ends with the budget, charged before the string is allocated.
    assertBudget(() -> filter.run(JqNull.NULL, NONE, new Results(), engine.newWorkspace()), Budget.BYTES_BUILT, HUGE + " under 1 MiB built");
  }

  @Test
  void theFourKibibyteCapHoldsForTheWholeFold() {
    // ADR-0035 (94509b6): [("x"*n),("x"*n)] + [] folds two strings of 40 + n bytes and an array of 64 + 2 x 8, all
    // under one budget of 40 + 4096. For n = 1988 that is 4136, a Const the run builds nothing for; for n = 1989 it is
    // 4138, so nothing folds whole (each repeat alone still does) and the run builds the array: under 0 bytes built it
    // ends with the budget (constant-key-cap/fold-whole-array gives jq-ref's output for n = 4000).
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(pair(1988)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of %s under 0 bytes built", pair(1988)).hasSize(1);
    assertBudget(
        () -> zero.compile(pair(1989)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()), Budget.BYTES_BUILT, pair(1989) + " under 0 bytes built");
  }

  @Test
  void theFoldsOfOneCompileBuildAtMostOneMebibyte() {
    // ADR-0035 (bd34167): every fold of a compile shares 1 MiB of bytes built. Each ("x"*4096) folds to a Const of
    // 40 + 4096 bytes, so 253 of them fold (1 046 408 bytes) and the 254th (1 050 544) is left to the run, which
    // builds it: under 0 bytes built the run of 253 gives them all, the run of 254 ends with the budget.
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(strings(253)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of 253 strings of 4096 bytes under 0 bytes built").hasSize(253);
    assertBudget(
        () -> zero.compile(strings(254)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "254 strings of 4096 bytes under 0 bytes built");
  }

  @Test
  void theFoldsOfOneCompileTakeAtMostTwoToTheTwentiethSteps() {
    // ADR-0035 (bd34167): every fold of a compile shares 2^20 steps. A difference of n ones less 1023 twos and a one
    // ticks per left element and per pair compared up to the one, n x 1024, and a few steps more (ADR-0034). n = 1023
    // folds alone and n = 1024 does not; a fold the run, under 0 bytes built, needs to build nothing for, one it is
    // left to, whose arrays it builds, ends with the budget (constant-key-cap/difference-past-the-step-budget gives
    // jq-ref's output for a difference past the steps).
    JqEngine zero = bytesBuilt(0);
    Results folded = new Results();
    zero.compile(difference(1023)).run(JqNull.NULL, NONE, folded, zero.newWorkspace());
    assertThat(folded.values).as("results of a difference of 1023 ones under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(difference(1024)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "a difference of 1024 ones under 0 bytes built");
    // Each of two differences of 512 ones folds alone, but together they pass 2^20: the second is left to the run.
    Results half = new Results();
    zero.compile(difference(512)).run(JqNull.NULL, NONE, half, zero.newWorkspace());
    assertThat(half.values).as("results of a difference of 512 ones under 0 bytes built").hasSize(1);
    assertBudget(
        () -> zero.compile(difference(512) + "," + difference(512)).run(JqNull.NULL, NONE, new Results(), zero.newWorkspace()),
        Budget.BYTES_BUILT,
        "two differences of 512 ones under 0 bytes built");
  }

  @Test
  void aFoldPastTheStepsLeavesTheLaterFoldsToTheRun() {
    // A fold that ends past the steps left has counted the tick that ended it; the later folds of the compile then
    // have none left, not fewer than none, and the compile goes on: 1 + 2 after it still gives 3.
    JqEngine unlimited = RunBudgets.unlimited();
    Results results = new Results();
    unlimited.compile(difference(1025) + ", 1 + 2").run(JqNull.NULL, NONE, results, unlimited.newWorkspace());
    assertThat(results.values).as("results of a difference past the steps, then 1 + 2").hasSize(2);
    assertThat(results.values.get(1).toJson()).as("1 + 2 after a difference past the steps").isEqualTo("3");
  }

  private static String pair(int n) {
    return "[(\"x\"*" + n + "),(\"x\"*" + n + ")] + []";
  }

  /** {@code n} strings of 4096 bytes, each the fold of a repeat, joined by commas. */
  private static String strings(int n) {
    return String.join(",", Collections.nCopies(n, "(\"x\"*4096)"));
  }

  /** {@code [1,...,1] - [2,...,2,1]}: {@code n} ones less 1023 twos and a one, n x 1024 ticks and a few more. */
  private static String difference(int n) {
    return "[" + String.join(",", Collections.nCopies(n, "1")) + "] - [" + "2,".repeat(1023) + "1]";
  }
}
