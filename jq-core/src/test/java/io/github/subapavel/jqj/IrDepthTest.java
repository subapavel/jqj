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
 * thread at 625 levels and overflowed at 650; warmed first, at 4 096 and not at 8 192. A warm or instrumented JVM can
 * need more stack than 512 KiB for 512 levels (F-44, measured at 4e9f444 and 025adcf): under JaCoCo a warmed JVM ran
 * out of it about 500 levels down. So the 512 KiB edges, 512 levels running and 513 and 30 001 a Stub, are pinned in
 * one fresh child JVM with no agent, the case a host meets; this JVM pins the bound's Stubs on a 16 MiB thread.
 */
class IrDepthTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** A stack the Resolver's 512 levels fit in whatever this JVM's state and instrumentation. */
  private static final long LARGE = 16L * 1024 * 1024;

  /** The brackets of each definition: fewer than the parser's 127 (DEVIATIONS "Parse depth"). */
  private static final int PER = 100;

  /** What {@link Child} prints for the three edges, in its order. */
  private static final String EDGES =
      "512 levels: " + "[".repeat(511) + "null" + "]".repeat(511) + "\n" + "513 levels: a Stub\n" + "30001 levels: a Stub\n";

  @TempDir Path dir;

  /** One child JVM, started with the test class path, no agent and no tool options, runs {@link Child}. */
  @Test
  void onA512KiBThreadInAFreshJvm512LevelsRunAnd513And30001AreStubs() throws IOException, InterruptedException {
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
    assertThat(Files.readString(out.toPath(), UTF_8)).as("what the child JVM printed").isEqualTo(EDGES);
    assertThat(child.exitValue()).as("the child JVM's exit code").isZero();
  }

  /** The bound's Stub; its 512 KiB stack claim is pinned by the child JVM's test. */
  @Test
  void aFilterOf513LevelsIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread(nested(513));
  }

  /** 300 definitions of 100 brackets; the bound's Stub, its 512 KiB stack claim pinned by the child JVM's test. */
  @Test
  void aFilterOf30001LevelsIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread(nested(30_001));
  }

  /**
   * The child JVM of {@link #onA512KiBThreadInAFreshJvm512LevelsRunAnd513And30001AreStubs}: on a 512 KiB thread each,
   * it runs 512 levels on null and compiles 513 and 30 001, in that order, and prints a line for each.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> got.set(results(nested(512))));
      System.out.println("512 levels: " + (thrown == null ? String.join(", ", got.get()) : thrown));
      for (int levels : new int[] {513, 30_001}) {
        Throwable stub = on(STACK, () -> Inlining.ENGINE.compile(nested(levels)));
        boolean isStub = stub instanceof JqCompileException && stub.getMessage().startsWith(MARK);
        System.out.println(levels + " levels: " + (isStub ? "a Stub" : stub == null ? "compiled" : stub));
      }
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

  private static void assertStubOnA16MiBThread(String filter) throws InterruptedException {
    Throwable thrown = on(LARGE, () -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 16 MiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
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
