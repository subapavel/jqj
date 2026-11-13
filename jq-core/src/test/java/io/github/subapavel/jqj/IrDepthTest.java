package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.Inlining.MARK;
import static io.github.subapavel.jqj.Inlining.results;
import static io.github.subapavel.jqj.Inlining.shown;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0040 "IR depth" (F-44): past {@code Resolver.MAX_IR_DEPTH} = 512 levels of built IR the filter is a Stub, as the
 * 512th nested expansion is. A level is one node of the IR on the way from its root to a leaf, the leaf counted: {@code
 * [[.]]} has three. Nested Collects reach any depth through definitions, each of {@link #PER} brackets around the
 * previous one's call, while the parser's 512 nesting levels (DEVIATIONS "Parse depth") bound each body alone. jq-ref
 * 1.8.2 runs every filter here (DEVIATIONS "IR depth").
 *
 * <p>#70's probe at f7670a7, one fresh JVM per depth and no bound: such Collects compiled and ran on a cold 512 KiB
 * thread at 625 levels and overflowed at 650; warmed first, at 4 096 and not at 8 192. With the bound (F-44, measured
 * at 4e9f444), 512 levels ran in every fresh JVM without an agent, but a warm or instrumented JVM can need more stack
 * than 512 KiB for them: after a Stubbed compile, 512 levels ran in 6 of 8 JVMs, and in 0 of 8 under JaCoCo. So the
 * 512-level run is pinned in a fresh child JVM with no agent, the case a host meets, and the Stubs in this JVM, each
 * after a 512-level run, the state in which the 513-level Stub overflowed at its throw under JaCoCo (4 of 4). Under
 * JaCoCo, after other runs (a Stub, then 512 levels again), the lowering itself overflowed about 500 levels down,
 * before the bound (#70, measured at 9fb4e33 with the signal throw).
 */
class IrDepthTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** The brackets of each definition: fewer than the parser's 127 (DEVIATIONS "Parse depth"). */
  private static final int PER = 100;

  /** The results of 512 levels on null: 511 nested arrays around null. */
  private static final String NESTED_511 = "[".repeat(511) + "null" + "]".repeat(511);

  @TempDir Path dir;

  /** One child JVM, started with the test class path, no agent and no tool options, runs {@link Child}. */
  @Test
  void aFilterOf512LevelsCompilesAndRunsOnA512KiBThreadInAFreshJvm() throws IOException, InterruptedException {
    File out = dir.resolve("out").toFile();
    ProcessBuilder launch =
        new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(out);
    // Either would reach the child's command line, and an agent there would make it the instrumented case.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    Process child = launch.start();
    assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("the child JVM ends within 120 s").isTrue();
    assertThat(Files.readString(out.toPath(), UTF_8)).as("what the child JVM printed").isEqualTo(NESTED_511 + "\n");
    assertThat(child.exitValue()).as("the child JVM's exit code").isZero();
  }

  @Test
  void aFilterOf513LevelsIsAStubAfterA512LevelRun() throws InterruptedException {
    assertStubOnA512KiBThreadAfterA512LevelRun(nested(513));
  }

  /** 300 definitions of 100 brackets: 30 001 levels, past what the Resolver lowers on a 512 KiB stack. */
  @Test
  void aFilterOf30001LevelsIsAStubAfterA512LevelRun() throws InterruptedException {
    assertStubOnA512KiBThreadAfterA512LevelRun(nested(30_001));
  }

  /**
   * The child JVM of {@link #aFilterOf512LevelsCompilesAndRunsOnA512KiBThreadInAFreshJvm}: it compiles and runs 512
   * levels on null on a 512 KiB thread and prints the one result, or what the thread threw and exits 1.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = onA512KiBThread(() -> got.set(results(nested(512))));
      if (thrown != null) {
        thrown.printStackTrace(System.out);
        System.exit(1);
      }
      System.out.println(String.join("\n", got.get()));
    }
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

  /**
   * Compiles and runs 512 levels first, on a thread of 16 MiB so that it ends whatever this JVM's state, and then
   * asserts that compiling {@code filter} on a 512 KiB thread is a Stub.
   */
  private static void assertStubOnA512KiBThreadAfterA512LevelRun(String filter) throws InterruptedException {
    AtomicReference<List<String>> got = new AtomicReference<>();
    assertThat(on(16L * 1024 * 1024, () -> got.set(results(nested(512))))).as("what 512 levels threw on a 16 MiB thread").isNull();
    assertThat(got.get()).as("results of 512 levels on null").isEqualTo(List.of(NESTED_511));
    Throwable thrown = onA512KiBThread(() -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 512 KiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
  }

  /** What {@code work} threw on a thread of ADR-0017's 512 KiB stack, or null. */
  private static Throwable onA512KiBThread(Runnable work) throws InterruptedException {
    return on(STACK, work);
  }

  /** What {@code work} threw on a thread of {@code stack} bytes, or null. */
  private static Throwable on(long stack, Runnable work) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread = new Thread(null, () -> {
      try {
        work.run();
      } catch (Throwable t) {
        failure.set(t);
      }
    }, stack + " bytes", stack);
    thread.start();
    thread.join();
    return failure.get();
  }
}
