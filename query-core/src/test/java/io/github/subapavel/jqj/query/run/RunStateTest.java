package io.github.subapavel.jqj.query.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * The run state below the public API (ADR-0030, #59), where a budget's largest value can be given directly. A budget
 * with no limit holds {@code Long.MAX_VALUE}: the engine's unlimited count, and its unlimited time as nanoseconds
 * ({@code Duration.ofNanos(Long.MAX_VALUE).toNanos()} is {@code Long.MAX_VALUE}, one nanosecond more throws
 * {@code ArithmeticException}; observed on JDK 25 on 2026-11-12). {@code System.nanoTime() + Long.MAX_VALUE} was
 * negative in the same observation, so a deadline kept as an end time would have passed at the first refuel.
 *
 * <p>The calls are the run state's protocol as ADR-0030 and ADR-0008 name it: {@code begin} with the run's steps,
 * deadline in nanoseconds and bytes written; {@code tick()} and {@code tick(int n)}; the charge of one drain's bytes
 * before it is written; {@code steps()}, the ticks counted in the run.
 */
class RunStateTest {
  /** 3 x (2^31 - 1): three of the largest ticks a call can charge, past what an int counts. */
  private static final long THREE_LARGEST = 3L * Integer.MAX_VALUE;

  @Test
  void unlimitedNeverOverflows() {
    RunState unlimited = new RunState();
    unlimited.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    for (int i = 0; i < 3; i++) {
      unlimited.chargeWritten(Integer.MAX_VALUE);
    }
    // Each tick of 2^31 - 1 overdraws the fuel, so each refuels: the step budget, the deadline and the interrupt are
    // checked three times against their largest values.
    for (int i = 0; i < 3; i++) {
      unlimited.tick(Integer.MAX_VALUE);
    }
    unlimited.tick();
    assertThat(unlimited.steps()).as("ticks counted with no step budget").isEqualTo(6_442_450_942L);
    unlimited.end();

    // Witness: the same ticks against a budget of exactly 3 x (2^31 - 1) steps pass, and the next one ends the run, so
    // the ticks above were checked and counted past 2^31.
    RunState steps = new RunState();
    steps.begin(THREE_LARGEST, Long.MAX_VALUE, Long.MAX_VALUE);
    for (int i = 0; i < 3; i++) {
      steps.tick(Integer.MAX_VALUE);
    }
    assertThat(steps.steps()).as("ticks counted at the step budget").isEqualTo(THREE_LARGEST);
    assertThatExceptionOfType(Termination.class).as("the tick after 3 x (2^31 - 1) steps").isThrownBy(steps::tick);

    // Witness: the same charges against exactly 3 x (2^31 - 1) bytes pass, and one more byte ends the run.
    RunState written = new RunState();
    written.begin(Long.MAX_VALUE, Long.MAX_VALUE, THREE_LARGEST);
    for (int i = 0; i < 3; i++) {
      written.chargeWritten(Integer.MAX_VALUE);
    }
    assertThatExceptionOfType(Termination.class).as("one byte after 3 x (2^31 - 1) bytes").isThrownBy(() -> written.chargeWritten(1));
  }
}
