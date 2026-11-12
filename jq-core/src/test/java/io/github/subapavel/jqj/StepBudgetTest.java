package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.assertBudget;
import static io.github.subapavel.jqj.RunBudgets.letters;
import static io.github.subapavel.jqj.RunBudgets.printed;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.steps;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Budget;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The step budget at the public API (ADR-0030, ADR-0008, #59). Before any loop exists the ticks are the reader's and the
 * writer's, one per started KiB (ADR-0015, F-23): a main read primes the Workspace's run state and the run that
 * consumes the document keeps the read's ticks; the writer ticks per drain of its 64 KiB buffer (ADR-0027). A run may
 * take exactly its budget of ticks; the next tick ends it with {@code JqTermination(BUDGET, STEPS)}.
 */
class StepBudgetTest {

  @Test
  void readTicksCountAgainstStepBudget() {
    JqEngine engine = steps(10);
    JqFilter dot = engine.compile(".");
    Workspace workspace = engine.newWorkspace();

    // 10 238 letters: 10 240 bytes, 10 ticks, the whole budget. The sink neither writes nor ticks.
    JqValue tenKiB = read(workspace, letters(10_238)).root();
    Results results = new Results();
    assertThat(dot.run(tenKiB, NONE, results, workspace)).as("run's answer over 10 240 bytes read").isTrue();
    assertThat(results.values).as("results over 10 240 bytes read").singleElement().isInstanceOf(JqString.class);
    assertThat(((JqString) results.values.get(0)).value()).as("the result's text").hasSize(10_238);

    // 10 239 letters: 10 241 bytes, 11 ticks. The run ends before it emits anything.
    JqValue overTenKiB = read(workspace, letters(10_239)).root();
    Results none = new Results();
    assertBudget(() -> dot.run(overTenKiB, NONE, none, workspace), Budget.STEPS, "run over 10 241 bytes read with 10 steps");
    assertThat(none.values).as("results of the run over 10 241 bytes read").isEmpty();

    // The bytes read count, not the size of the value: 1 and 10 240 spaces is 10 241 bytes, 11 ticks.
    JqValue spacedOne = read(workspace, bytes("1" + " ".repeat(10_240))).root();
    assertBudget(() -> dot.run(spacedOne, NONE, new Results(), workspace), Budget.STEPS, "run over 1 and 10 240 spaces with 10 steps");
    // Witness: 1 and 10 239 spaces, 10 240 bytes, completes.
    JqValue one = read(workspace, bytes("1" + " ".repeat(10_239))).root();
    Results number = new Results();
    assertThat(dot.run(one, NONE, number, workspace)).as("run's answer over 1 and 10 239 spaces").isTrue();
    assertThat(number.values).as("results over 1 and 10 239 spaces").singleElement().isInstanceOf(JqNumber.class);
    assertThat(((JqNumber) number.values.get(0)).longValue()).as("the result").isEqualTo(1L);
  }

  @Test
  void zeroStepsEndsAtFirstTick() {
    JqEngine engine = steps(0);
    JqFilter dot = engine.compile(".");

    // A run with no tick at all: nothing was read on this Workspace and the sink does not write.
    Workspace idle = engine.newWorkspace();
    Results results = new Results();
    assertThat(dot.run(JqNull.NULL, NONE, results, idle)).as("run's answer for a run without a tick").isTrue();
    assertThat(results.values).as("results of the run without a tick").containsExactly(JqNull.NULL);

    // The read's one tick: the document 1, one byte.
    JqValue one = read(idle, bytes("1")).root();
    Results none = new Results();
    assertBudget(() -> dot.run(one, NONE, none, idle), Budget.STEPS, "run over one byte read with 0 steps");
    assertThat(none.values).as("results of the run over one byte read").isEmpty();

    // The writer's one tick: null and an LF, 5 bytes, on a Workspace that read nothing.
    Workspace writing = engine.newWorkspace();
    assertBudget(
        () -> dot.run(JqNull.NULL, NONE, writer(writing, new RecordingTarget()), writing), Budget.STEPS, "run writing null with 0 steps");
    // Witness: one step is enough for that tick. jq-ref 1.8.2: null under -c . prints null and an LF.
    JqEngine oneStep = steps(1);
    Workspace witness = oneStep.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    assertThat(oneStep.compile(".").run(JqNull.NULL, NONE, writer(witness, target), witness)).as("run's answer writing null with 1 step").isTrue();
    assertThat(target.text()).as("output writing null with 1 step").isEqualTo("null\n");
  }

  @Test
  void writerTicksCountAgainstStepBudget() {
    // The documents are read on a Workspace of another engine: its read ticks are its own, so only the writer's ticks
    // reach the run's Workspace.
    Workspace reading = JqEngine.builder().build().newWorkspace();
    JqEngine engine = steps(10);
    JqFilter dot = engine.compile(".");
    Workspace workspace = engine.newWorkspace();

    // 10 237 letters print 10 240 bytes in one drain: 10 ticks, the whole budget.
    JqValue tenKiB = read(reading, letters(10_237)).root();
    RecordingTarget target = new RecordingTarget();
    assertThat(dot.run(tenKiB, NONE, writer(workspace, target), workspace)).as("run's answer printing 10 240 bytes").isTrue();
    assertThat(target.text()).as("output printing 10 240 bytes").isEqualTo(printed(10_237));

    // 10 238 letters print 10 241 bytes: 11 ticks.
    JqValue overTenKiB = read(reading, letters(10_238)).root();
    assertBudget(
        () -> dot.run(overTenKiB, NONE, writer(workspace, new RecordingTarget()), workspace), Budget.STEPS, "run printing 10 241 bytes with 10 steps");
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  /**
   * The cut is exact: a run over a document read with {@code r} ticks that prints one line of {@code w} ticks completes
   * under a budget of r + w steps and ends under r + w - 1. Each seed picks r and w first, as KiB counts, and then builds
   * the document from them: a string whose printed line (the string and an LF, at most 64 KiB, so one drain) is longer
   * than w - 1 KiB and at most w KiB, followed or preceded by spaces up to a length longer than r - 1 KiB and at most r
   * KiB (ADR-0015, F-23: one tick per started KiB).
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void stepBudgetCutsExactly(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    int writeTicks = random.nextInt(1, 65);
    int readTicks = writeTicks + random.nextInt(0, 21);
    // The printed line: the letters, two quotes and the LF, so at least 3 bytes.
    int line = Math.max(3, random.nextInt((writeTicks - 1) * 1024 + 1, writeTicks * 1024 + 1));
    int letters = line - 3;
    int text = letters + 2;
    int length = random.nextInt(Math.max(text, (readTicks - 1) * 1024 + 1), readTicks * 1024 + 1);
    int before = random.nextInt(0, length - text + 1);
    byte[] document = new byte[length];
    Arrays.fill(document, (byte) ' ');
    System.arraycopy(letters(letters), 0, document, before, text);
    long ticks = readTicks + writeTicks;
    String what = String.format("seed %d: %d letters at %d in %d bytes (%d ticks read, %d printed)", seed, letters, before, length, readTicks, writeTicks);

    JqEngine under = steps(ticks - 1);
    Workspace cut = under.newWorkspace();
    JqValue cutRoot = read(cut, document).root();
    assertBudget(() -> under.compile(".").run(cutRoot, NONE, writer(cut, new RecordingTarget()), cut), Budget.STEPS, what + " with one step too few");

    JqEngine exact = steps(ticks);
    Workspace whole = exact.newWorkspace();
    JqValue wholeRoot = read(whole, document).root();
    RecordingTarget target = new RecordingTarget();
    assertThat(exact.compile(".").run(wholeRoot, NONE, writer(whole, target), whole)).as("%s: run's answer with exactly its steps", what).isTrue();
    assertThat(target.text()).as("%s: output with exactly its steps", what).isEqualTo(printed(letters));
  }

  private static byte[] bytes(String ascii) {
    return ascii.getBytes(US_ASCII);
  }
}
