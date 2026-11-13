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
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0040 "IR depth" (F-44): past {@code Resolver.MAX_IR_DEPTH} = 512 levels of built IR the filter is a Stub, as the
 * 512th nested expansion is. A level is one node of the IR on the way from its root to a leaf, the leaf counted: {@code
 * [[.]]} has three. The depth is the built IR's wherever the Resolver builds it (ADR-0040 "What counts"): nested
 * Collects through definitions, each of {@link #PER} brackets around the previous one's call, while the parser's 512
 * nesting levels (DEVIATIONS "Parse depth") bound each body alone; the Bind of each value parameter around the body of
 * nested expansions; the Bind of each computed-key step around the chain before it; and the levels of a wide
 * construct, such as a chain of operators. jq-ref 1.8.2 compiles every filter here (DEVIATIONS "IR depth").
 *
 * <p>#70's probe at f7670a7, one fresh JVM per depth and no bound: such Collects compiled and ran on a cold 512 KiB
 * thread at 625 levels and overflowed at 650; warmed first, at 4 096 and not at 8 192. A warm or instrumented JVM can
 * need more stack than 512 KiB for 512 levels (F-44, measured at 4e9f444 and 025adcf): under JaCoCo a warmed JVM ran
 * out of it about 500 levels down. So the 512 KiB edges are pinned in one fresh child JVM with no agent, the case a host
 * meets; this JVM pins the bound's logic on a 16 MiB thread.
 */
class IrDepthTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** A stack the Resolver's 512 levels fit in whatever this JVM's state and instrumentation. */
  private static final long LARGE = 16L * 1024 * 1024;

  /** The brackets of each definition: fewer than the parser's 127 (DEVIATIONS "Parse depth"). */
  private static final int PER = 100;

  /** jq-ref 1.8.2's result for {@code nested(512)} on null (observed 2026-11-13, -nc -f): 511 nested arrays. */
  private static final String NESTED_512 = "[".repeat(511) + "null" + "]".repeat(511);

  /** 300 indexes by a computed key, each bound around the chain before it: past the bound alone. */
  private static final String DEEP = "." + "[.a]".repeat(300);

  /** What {@link Child} prints for its edges, in its order. */
  private static final String EDGES =
      "512 levels: " + NESTED_512 + "\n" + "513 levels: a Stub\n" + "30001 levels: a Stub\n" + "500 value parameters: a Stub\n" + "10000 computed indexes: a Stub\n";

  @TempDir Path dir;

  /**
   * One child JVM, started with the test class path, no agent and no tool or JVM options, runs {@link Child}: the
   * stack claim of every test here.
   */
  @Test
  void onA512KiBThreadInAFreshJvm512LevelsRunAndDeeperFiltersAreStubs() throws IOException, InterruptedException {
    assertChildPrintsTheEdges();
  }

  /**
   * The same child JVM with C1 only and compiles in the foreground, the state of a loaded machine whose C2 compiles
   * lag (#86): the 512 levels the child runs first get the Resolver C1-compiled, and the descent of 513 levels ran out
   * of 512 KiB about 500 levels down, before the bound's Stub (#86, 5 of 5 runs before the lowering's frames were made small).
   */
  @Test
  void onA512KiBThreadInAFreshC1OnlyJvm512LevelsRunAndDeeperFiltersAreStubs() throws IOException, InterruptedException {
    assertChildPrintsTheEdges("-Xbatch", "-XX:TieredStopAtLevel=1");
  }

  /** Runs {@link Child} in a fresh JVM with {@code flags} and asserts it prints {@link #EDGES}. */
  private void assertChildPrintsTheEdges(String... flags) throws IOException, InterruptedException {
    File out = dir.resolve("out").toFile();
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(List.of(flags));
    command.addAll(List.of("-cp", System.getProperty("java.class.path"), Child.class.getName()));
    ProcessBuilder launch = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out);
    // Each would reach the child's command line, and an agent there would make it the instrumented case.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("the child JVM ends within 120 s").isTrue();
      assertThat(Files.readString(out.toPath(), UTF_8)).as("what the child JVM printed").isEqualTo(EDGES);
      assertThat(child.exitValue()).as("the child JVM's exit code").isZero();
    } finally {
      child.destroyForcibly();
    }
  }

  /**
   * 512 levels are not past the bound: jq-ref 1.8.2 (observed 2026-11-13, the filter read with -f) prints the 511
   * nested arrays around null. Their 512 KiB stack claim is pinned by the child JVM's test.
   */
  @Test
  void aFilterOf512LevelsRuns() throws InterruptedException {
    assertThat(resultsOnA16MiBThread(nested(512))).as("results of 512 levels on null").isEqualTo(List.of(NESTED_512));
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
   * 500 nested expansions of one value parameter each around 501 levels: each Bind of {@code $a} is a level of its own
   * (F-44, the #70 review at b0b5095). Its 512 KiB stack claim is pinned by the child JVM's test.
   */
  @Test
  void nestedValueParameterExpansionsAreAStub() throws InterruptedException {
    assertStubOnA16MiBThread(valueParameters(500));
  }

  /** Each {@code [.a:]} binds its bound around the chain before it (F-44, the #70 review at b0b5095). */
  @Test
  void aChainOf2000ComputedSlicesIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread("." + "[.a:]".repeat(2_000));
  }

  /** Each {@code [.a]} binds its key around the chain before it (F-44, the #70 review at b0b5095). */
  @Test
  void aChainOf10000ComputedIndexesIsAStub() throws InterruptedException {
    assertStubOnA16MiBThread("." + "[.a]".repeat(10_000));
  }

  /**
   * The bound on the built IR's levels where the lowering counts fewer: each {@code .a+} binds both its operands, two
   * levels a term, so 256 terms are 512 levels and run, null on null as jq-ref 1.8.2 prints (observed 2026-11-13, the
   * filter read with -f), and in brackets they are 513, a Stub, while the lowering is inside 257 Asts at most.
   */
  @Test
  void anOperatorChainOf512LevelsRunsAndOf513IsAStub() throws InterruptedException {
    String chain = terms(256, "+", ".a");
    assertThat(resultsOnA16MiBThread(chain)).as("results of 256 terms of .a+ on null").isEqualTo(List.of("null"));
    assertStubOnA16MiBThread("[" + chain + "]");
  }

  /**
   * A value parameter's level ends with its expansion: 600 calls of {@code def f($a): .} side by side each bind
   * {@code $a} once, so the lowering is inside one such level at a time and the filter runs. jq-ref 1.8.2 (observed
   * 2026-11-13, the filter read with -f) prints the array of 600 nulls.
   */
  @Test
  void callsOfAValueParameterDefinitionSideBySideRun() {
    String filter = "def f($a): .; [" + terms(600, ", ", "f(1)") + "]";
    assertThat(results(filter)).as("results of 600 calls of f($a) on null").isEqualTo(List.of("[" + terms(600, ",", "null") + "]"));
  }

  /**
   * The bound counts every part of a node that IrVerifier visits: {@link #DEEP}, a chain past the bound, placed in
   * one part of one node kind, is a Stub, whichever the part.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("deepParts")
  void aChainPastTheBoundInAnyPartOfANodeIsAStub(String part, String filter) throws InterruptedException {
    assertStubOnA16MiBThread(filter);
  }

  static Stream<Arguments> deepParts() {
    return Stream.of(
        Arguments.of("a Pipe's left", DEEP + " | .a"),
        Arguments.of("a Pipe's right", ".a | " + DEEP),
        Arguments.of("a Comma's left", DEEP + ", 1"),
        Arguments.of("a Comma's right", "1, " + DEEP),
        Arguments.of("a Collect's body", "[" + DEEP + "]"),
        Arguments.of("an If's condition", "if " + DEEP + " then . else . end"),
        Arguments.of("an If's then", "if . then " + DEEP + " else . end"),
        Arguments.of("an If's otherwise", "if . then . else " + DEEP + " end"),
        Arguments.of("an Alt's left", DEEP + " // 1"),
        Arguments.of("an Alt's right", "1 // " + DEEP),
        Arguments.of("a Bind's value", DEEP + " as $v | ."),
        Arguments.of("a Bind's body", ". as $v | " + DEEP),
        Arguments.of("an AltBind's value", DEEP + " as [$a] ?// $a | ."),
        Arguments.of("an AltBind's match", ". as [$a] ?// {(" + DEEP + "): $a} | ."),
        Arguments.of("an AltBind's body", ". as [$a] ?// $a | " + DEEP),
        Arguments.of("a Try's body", "try " + DEEP),
        Arguments.of("a Try's handler", "try . catch " + DEEP),
        Arguments.of("a Label's body", "label $l | " + DEEP),
        Arguments.of("a Reduce's init", "reduce . as $x (" + DEEP + "; .)"),
        Arguments.of("a Reduce's each", "reduce " + DEEP + " as $x (0; .)"),
        Arguments.of("an Advance's update in a Reduce", "reduce . as $x (0; " + DEEP + ")"),
        Arguments.of("a Foreach's init", "foreach . as $x (" + DEEP + "; .)"),
        Arguments.of("a Foreach's each", "foreach " + DEEP + " as $x (0; .)"),
        Arguments.of("an Advance's update in a Foreach", "foreach . as $x (0; " + DEEP + ")"));
  }

  /**
   * A construct whose IR is as deep as it is wide, past the bound (F-44, the #70 review at b0b5095), as DEVIATIONS "IR
   * depth" names it with jq-ref 1.8.2's result: an operator between two filters binds both, two levels a term, and
   * one between variables; {@code and} and {@code or} are a level a term; an object's computed value is a level and
   * its computed key two; an interpolation, a pattern's variable and a value parameter are a level each.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wideConstructs")
  void aWideConstructPastTheBoundIsAStub(String construct, String filter) throws InterruptedException {
    assertStubOnA16MiBThread(filter);
  }

  static Stream<Arguments> wideConstructs() {
    return Stream.of(
        Arguments.of("257 terms of .a+", terms(257, "+", ".a")),
        Arguments.of("257 terms of .a*", terms(257, "*", ".a")),
        Arguments.of("600 terms of $x+", "1 as $x | " + terms(600, "+", "$x")),
        Arguments.of("600 terms of and", terms(600, " and ", ".a")),
        Arguments.of("600 terms of or", terms(600, " or ", ".a")),
        Arguments.of("600 computed values", "{" + terms(600, ", ", "k%d: .a") + "}"),
        Arguments.of("600 keys without values", "{" + terms(600, ", ", "k%d") + "}"),
        Arguments.of("300 computed keys", "{\"a\": \"k\"} | {" + terms(300, ", ", "(.a): 1") + "}"),
        Arguments.of("600 interpolations", "\"" + "\\(.a)".repeat(600) + "\""),
        Arguments.of("an array pattern of 600 variables", ". as [" + terms(600, ", ", "$a%d") + "] | 1"),
        Arguments.of("an object pattern of 600 keys", ". as {" + terms(600, ", ", "k%d: $a%d") + "} | 1"),
        Arguments.of("an object pattern of 600 variables", ". as {" + terms(600, ", ", "$a%d") + "} | 1"),
        Arguments.of("600 value parameters", "def f(" + terms(600, "; ", "$a%d") + "): 1; f(" + terms(600, "; ", "1") + ")"));
  }

  /** {@code n} copies of {@code term}, each {@code %d} in it the copy's number from 0, joined by {@code separator}. */
  private static String terms(int n, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = 0; i < n; i++) {
      joined.add(term.replace("%d", Integer.toString(i)));
    }
    return joined.toString();
  }

  /**
   * The child JVM of {@link #onA512KiBThreadInAFreshJvm512LevelsRunAndDeeperFiltersAreStubs}: on a 512 KiB thread
   * each, it runs 512 levels on null and compiles 513 levels, 30 001 levels, 500 value parameters and 10 000 computed
   * indexes, in that order, and prints a line for each. The lowering's count stops the first three Stubs; only the walk
   * of the built IR stops the last.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> got.set(results(nested(512))));
      System.out.println("512 levels: " + (thrown == null ? String.join(", ", got.get()) : thrown));
      stub("513 levels", nested(513));
      stub("30001 levels", nested(30_001));
      stub("500 value parameters", valueParameters(500));
      stub("10000 computed indexes", "." + "[.a]".repeat(10_000));
    }

    private static void stub(String name, String filter) throws InterruptedException {
      Throwable stub = on(STACK, () -> Inlining.ENGINE.compile(filter));
      boolean isStub = stub instanceof JqCompileException && stub.getMessage().startsWith(MARK);
      System.out.println(name + ": " + (isStub ? "a Stub" : stub == null ? "compiled" : stub));
    }
  }

  /**
   * {@code levels} - 1 brackets around {@code .}, through definitions: {@code def f1: [[…[.]…]]; def f2: [[…[f1]…]];
   * …} and the main filter's rest of brackets around the last call, so its IR has {@code levels} levels.
   */
  private static String nested(int levels) {
    return definitions("f", levels) + call("f", levels);
  }

  /**
   * The #70 review's filter: {@code def g1: …; … def g5: …;} of 501 levels as {@link #nested} builds them, then {@code
   * def v1($a): g5; def v2($a): v1(.); …}, called as {@code vn(.)}: n expansions, each binding {@code $a} around the
   * next.
   */
  private static String valueParameters(int n) {
    StringBuilder f = new StringBuilder(definitions("g", 501)).append("def v1($a): ").append(call("g", 501)).append("; ");
    for (int i = 2; i <= n; i++) {
      f.append("def v").append(i).append("($a): v").append(i - 1).append("(.); ");
    }
    return f.append('v').append(n).append("(.)").toString();
  }

  /** {@code def <name>1: [[…[.]…]]; def <name>2: [[…[<name>1]…]]; …}, {@link #PER} brackets each, for {@code levels}. */
  private static String definitions(String name, int levels) {
    StringBuilder f = new StringBuilder();
    int defs = (levels - 1) / PER;
    for (int i = 1; i <= defs; i++) {
      f.append("def ").append(name).append(i).append(": ").append("[".repeat(PER)).append(i == 1 ? "." : name + (i - 1)).append("]".repeat(PER)).append("; ");
    }
    return f.toString();
  }

  /** The rest of the brackets of {@code levels} around the last of {@link #definitions}, or around {@code .}. */
  private static String call(String name, int levels) {
    int defs = (levels - 1) / PER;
    int rest = (levels - 1) % PER;
    return "[".repeat(rest) + (defs == 0 ? "." : name + defs) + "]".repeat(rest);
  }

  private static void assertStubOnA16MiBThread(String filter) throws InterruptedException {
    Throwable thrown = on(LARGE, () -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 16 MiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
  }

  /** The results of {@code filter} on null on a 16 MiB thread; what the run threw fails the test with it as the cause. */
  private static List<String> resultsOnA16MiBThread(String filter) throws InterruptedException {
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(results(filter)));
    if (thrown != null) {
      throw new AssertionError("running " + shown(filter) + " on a 16 MiB thread threw", thrown);
    }
    return got.get();
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
