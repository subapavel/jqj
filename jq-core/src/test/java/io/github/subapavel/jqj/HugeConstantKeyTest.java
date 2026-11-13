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

  private static String pair(int n) {
    return "[(\"x\"*" + n + "),(\"x\"*" + n + ")] + []";
  }
}
