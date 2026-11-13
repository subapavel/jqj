package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.MARK;
import static io.github.subapavel.jqj.Inlining.results;
import static io.github.subapavel.jqj.Inlining.shown;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * ADR-0040 "IR depth" (F-44): past {@code Resolver.MAX_IR_DEPTH} = 512 levels of built IR the filter is a Stub, as the
 * 512th nested expansion is. A level is one node of the IR on the way from its root to a leaf, the leaf counted: {@code
 * [[.]]} has three. Nested Collects reach any depth through definitions, each of {@link #PER} brackets around the
 * previous one's call, while the parser's 512 nesting levels (DEVIATIONS "Parse depth") bound each body alone. Each
 * test compiles on a thread of ADR-0017's 512 KiB stack. jq-ref 1.8.2 runs every filter here (DEVIATIONS "IR depth").
 *
 * <p>#70's probe at f7670a7, one fresh JVM per depth and no bound: such Collects compiled and ran on a cold 512 KiB
 * thread at 625 levels and overflowed at 650; warmed first, at 4 096 and not at 8 192.
 */
class IrDepthTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** The brackets of each definition: fewer than the parser's 127 (DEVIATIONS "Parse depth"). */
  private static final int PER = 100;

  @Test
  void aFilterOf512LevelsCompilesAndRunsOnA512KiBThread() throws InterruptedException {
    AtomicReference<List<String>> got = new AtomicReference<>();
    String filter = nested(512);
    assertThat(onA512KiBThread(() -> got.set(results(filter)))).as("what %s threw on a 512 KiB thread", shown(filter)).isNull();
    assertThat(got.get()).as("results of %s on null", shown(filter)).isEqualTo(List.of("[".repeat(511) + "null" + "]".repeat(511)));
  }

  @Test
  void aFilterOf513LevelsIsAStub() throws InterruptedException {
    assertStubOnA512KiBThread(nested(513));
  }

  /** 300 definitions of 100 brackets: 30 001 levels, past what the Resolver lowers on a 512 KiB stack. */
  @Test
  void aFilterOf30001LevelsIsAStubAndNoStackOverflow() throws InterruptedException {
    assertStubOnA512KiBThread(nested(30_001));
  }

  /**
   * {@code levels} - 1 brackets around {@code .}, through definitions: {@code def f1: [[…[.]…]]; def f2: [[…[f1]…]];
   * …} and the main filter's rest of brackets around the last call, so its IR has {@code levels} levels.
   */
  private static String nested(int levels) {
    int brackets = levels - 1;
    int defs = brackets / PER;
    StringBuilder f = new StringBuilder();
    for (int i = 1; i <= defs; i++) {
      f.append("def f").append(i).append(": ").append("[".repeat(PER)).append(i == 1 ? "." : "f" + (i - 1)).append("]".repeat(PER)).append("; ");
    }
    int rest = brackets % PER;
    return f.append("[".repeat(rest)).append(defs == 0 ? "." : "f" + defs).append("]".repeat(rest)).toString();
  }

  private static void assertStubOnA512KiBThread(String filter) throws InterruptedException {
    Throwable thrown = onA512KiBThread(() -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 512 KiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
  }

  /** What {@code work} threw on a thread of ADR-0017's 512 KiB stack, or null. */
  private static Throwable onA512KiBThread(Runnable work) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread small = new Thread(null, () -> {
      try {
        work.run();
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "512 KiB", STACK);
    small.start();
    small.join();
    return failure.get();
  }
}
