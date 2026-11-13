package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
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
}
