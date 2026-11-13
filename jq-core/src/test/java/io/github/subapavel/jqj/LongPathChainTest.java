package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

  @ParameterizedTest(name = "{0} steps")
  @ValueSource(ints = {21_844, 100_000})
  void aLongFieldChainCompilesAndRunsOnTheTestThread(int steps) {
    assertThat(results(".a".repeat(steps))).as("results of %d steps of .a on null", steps).isEqualTo(List.of("null"));
  }
}
