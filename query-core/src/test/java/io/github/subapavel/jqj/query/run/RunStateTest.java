package io.github.subapavel.jqj.query.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.run.Termination.Budget;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The run state below the public API (ADR-0030, #59), where a budget's largest value can be given directly. An
 * unlimited budget holds {@code Long.MAX_VALUE}: the engine's unlimited count, and its unlimited time as nanoseconds
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
    assertBudget(steps::tick, Budget.STEPS, "the tick after 3 x (2^31 - 1) steps");

    // Witness: the same charges against exactly 3 x (2^31 - 1) bytes pass, and one more byte ends the run.
    RunState written = new RunState();
    written.begin(Long.MAX_VALUE, Long.MAX_VALUE, THREE_LARGEST);
    for (int i = 0; i < 3; i++) {
      written.chargeWritten(Integer.MAX_VALUE);
    }
    assertBudget(() -> written.chargeWritten(1), Budget.BYTES_WRITTEN, "one byte after 3 x (2^31 - 1) bytes");
  }

  /** ADR-0008, ADR-0017: refuel's interval. */
  private static final int INTERVAL = 4096;

  /**
   * Refuel comes every 4096 ticks, and only there is the thread's interrupt seen (ADR-0030, ADR-0017): a run interrupted
   * after one refuel goes on for a whole interval, then ends with CANCELLED at the next tick, the flag still set.
   */
  @Test
  void interruptEndsTheRunAtTheNextRefuel() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    try {
      // The first refuel, at tick 4097, passes: nothing is over and the thread is not interrupted.
      for (int i = 0; i < INTERVAL + 1; i++) {
        state.tick();
      }
      assertThat(state.steps()).as("ticks after the first refuel").isEqualTo(INTERVAL + 1L);

      Thread.currentThread().interrupt();
      // A whole interval of ticks after the refuel reaches no refuel, so the interrupt is not seen yet.
      state.tick(INTERVAL);
      assertThat(state.steps()).as("ticks after one more interval").isEqualTo(2L * INTERVAL + 1);
      Termination cancelled = assertThatExceptionOfType(Termination.class).as("the tick after it").isThrownBy(state::tick).actual();
      assertThat(cancelled.cause()).as("cause of the interrupted run").isEqualTo(Termination.Cause.CANCELLED);
      assertThat(cancelled.budget()).as("budget of the interrupted run").isNull();
      assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag after the run was cancelled").isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /**
   * The deadline is checked at refuel only, after 4096 ticks of the run (ADR-0030, F-36). A deadline of 1 ns has passed
   * by the time refuel checks it; the clock cannot be faked here (F-37), so the run ticks until a refuel ends it, and
   * the tick that ended it is a refuel's.
   */
  @Test
  void deadlineEndsTheRunAtARefuel() {
    RunState late = new RunState();
    late.begin(Long.MAX_VALUE, 1, Long.MAX_VALUE);
    assertBudget(
        () -> {
          for (long i = 0; i < (long) INTERVAL * INTERVAL; i++) {
            late.tick();
          }
        },
        Budget.DEADLINE,
        "ticks with a 1 ns deadline");
    assertThat(late.steps()).as("ticks when the deadline ended the run").isGreaterThan(INTERVAL);
    assertThat((late.steps() - 1) % INTERVAL).as("ticks past the last interval when the deadline ended the run").isZero();

    // Witness: with an unlimited deadline the same ticks pass every refuel.
    RunState unlimited = new RunState();
    unlimited.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    for (int i = 0; i < 3 * INTERVAL; i++) {
      unlimited.tick();
    }
    assertThat(unlimited.steps()).as("ticks with an unlimited deadline").isEqualTo(3L * INTERVAL);
  }

  /**
   * A main read primes the run state (ADR-0030): the next run keeps the read's ticks, and ends at its begin when they
   * already pass its step budget. That run is then not running, and the ticks are charged once.
   */
  @Test
  void primedTicksCountAgainstTheNextRun() {
    RunState exact = new RunState();
    exact.prime();
    exact.tick(10);
    exact.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(exact.steps()).as("ticks kept from the primed read").isEqualTo(10L);
    assertBudget(exact::tick, Budget.STEPS, "the first tick of a run that kept 10 ticks with 10 steps");

    RunState over = new RunState();
    over.prime();
    over.tick(11);
    assertBudget(() -> over.begin(10, Long.MAX_VALUE, Long.MAX_VALUE), Budget.STEPS, "begin after 11 primed ticks with 10 steps");
    // The run that ended at its begin is not running and took the read with it: the next one begins from no tick.
    over.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(over.steps()).as("ticks at the begin after the one that ended").isZero();
    over.tick(10);
    over.end();

    // Witness: ticks outside a run with no read before them are not kept.
    RunState idle = new RunState();
    idle.tick(11);
    idle.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(idle.steps()).as("ticks at a begin with no primed read").isZero();
  }

  /** A read during a run is charged to that run (ADR-0030: reads during a run count too): it does not prime. */
  @Test
  void readDuringARunIsChargedToIt() {
    RunState state = new RunState();
    state.begin(10, Long.MAX_VALUE, Long.MAX_VALUE);
    state.tick(5);
    state.prime();
    assertThat(state.steps()).as("ticks after a read during the run").isEqualTo(5L);
    state.tick(5);
    assertBudget(state::tick, Budget.STEPS, "the eleventh tick of the run, a read in between");
  }

  /** A run inside a running one is refused, and the running one keeps its budgets (ADR-0030, ADR-0016). */
  @Test
  void beginWhileRunningIsIllegalState() {
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, 4);
    assertThatExceptionOfType(IllegalStateException.class)
        .as("begin while running")
        .isThrownBy(() -> state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    assertBudget(() -> state.chargeWritten(5), Budget.BYTES_WRITTEN, "5 bytes with the running run's limit of 4");

    // Witness: after end the next run begins, with its own budget.
    state.end();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    state.chargeWritten(5);
    assertThat(state.steps()).as("ticks of the next run").isZero();
  }

  /** Asserts that {@code call} ends the run with {@code Termination(BUDGET, budget)} and the budget's message. */
  private static void assertBudget(ThrowingCallable call, Budget budget, String what) {
    Termination t = assertThatExceptionOfType(Termination.class).as("%s", what).isThrownBy(call).actual();
    assertThat(t.cause()).as("cause of %s", what).isEqualTo(Termination.Cause.BUDGET);
    assertThat(t.budget()).as("budget of %s", what).isEqualTo(budget);
    assertThat(t.getMessage()).as("message of %s", what).isEqualTo(MESSAGES.get(budget));
  }

  /** ADR-0030 and the design approval of #59: the budgets' messages. */
  private static final Map<Budget, String> MESSAGES =
      Map.of(
          Budget.STEPS, "Step budget exceeded",
          Budget.DEADLINE, "Deadline exceeded",
          Budget.BYTES_WRITTEN, "Bytes-written budget exceeded");
}
