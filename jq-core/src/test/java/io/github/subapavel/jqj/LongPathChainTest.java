package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A long {@code .a.a.a…} chain compiles and runs without a StackOverflowError (#70 design approval, O3): the parser
 * reads the postfix steps in a loop, so the Resolver and the run meet a chain as long as the filter. On null each
 * {@code .a} is null, so the result is one null. jq-ref 1.8.2 (observed 2026-11-13) prints null for 21 844 steps and
 * rejects 21 845 with {@code function compiled to 65537 bytes which is too long}, rc 3; 10⁵ steps is past jq's
 * bytecode limit, as the long comma chains of {@code CommaChainTest} are (DEVIATIONS "Long comma chains").
 */
class LongPathChainTest {

  /** ADR-0017's default stack for a run, as {@code CommaChainTest} takes it (#70 design approval, open question 2). */
  private static final long STACK = 512 * 1024;

  /** One read more than the Resolver keeps as steps of their own (F-42): the run is one fused step. */
  private static final int FUSED = 513;

  @ParameterizedTest(name = "{0} steps")
  @ValueSource(ints = {21_844, 100_000})
  void aLongFieldChainCompilesAndRunsOnTheTestThread(int steps) {
    assertThat(results(".a".repeat(steps))).as("results of %d steps of .a on null", steps).isEqualTo(List.of("null"));
  }

  @ParameterizedTest(name = "{0} steps on a 512 KiB thread")
  @ValueSource(ints = {21_844, 100_000})
  void aLongFieldChainCompilesAndRunsOnA512KiBThread(int steps) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<List<String>> got = new AtomicReference<>();
    Thread small = new Thread(null, () -> {
      try {
        got.set(results(".a".repeat(steps)));
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "512 KiB", STACK);
    small.start();
    small.join();
    assertThat(failure.get()).as("what %d steps of .a threw on a 512 KiB thread", steps).isNull();
    assertThat(got.get()).as("results of %d steps of .a on null on a 512 KiB thread", steps).isEqualTo(List.of("null"));
  }

  /** jq-ref 1.8.2, observed 2026-11-13 with -nc -f: 7, rc 0. Each read of the fused run reads its predecessor's result. */
  @Test
  void aFusedRunReadsEachFieldOfThePreviousResult() {
    String filter = "reduce range(0; " + FUSED + ") as $i (7; {a: .}) | " + ".a".repeat(FUSED);
    assertThat(results(filter)).as("results of %d reads into %d nested objects", FUSED, FUSED).isEqualTo(List.of("7"));
  }

  /**
   * jq-ref 1.8.2, observed 2026-11-13 with -nc -f: []. The quiet second read has no result on 1, so the run ends there:
   * the plain reads after it never meet the missing value.
   */
  @Test
  void aQuietReadWithoutAResultEndsTheFusedRun() {
    String filter = "[{\"a\": 1} | .a.a?" + ".a".repeat(FUSED - 2) + "]";
    assertThat(results(filter)).as("results of a fused run whose quiet read has no result").isEqualTo(List.of("[]"));
  }

  /** jq-ref 1.8.2, observed 2026-11-13 with -nc -f: the second read's error, caught. */
  @Test
  void aPlainReadThatFailsRaisesFromTheFusedRun() {
    String filter = "try ({\"a\": 1} | " + ".a".repeat(FUSED) + ") catch .";
    assertThat(results(filter)).as("results of a fused run whose second read fails").isEqualTo(List.of("\"Cannot index number with string (\\\"a\\\")\""));
  }
}
