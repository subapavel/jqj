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
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #86 (F-44 "Not changed here"): the parser builds a left-associative operator chain ({@code +}, {@code and}, {@code
 * or}, a comparison inside one) in a loop, so the Binder and the Resolver meet a chain as long as the filter. They walk
 * it without a Java frame per term, so a chain past {@code Resolver.MAX_IR_DEPTH} is a Stub (DEVIATIONS "IR depth",
 * which #87 lifts), not a StackOverflowError, and a chain of constants folds to its Const.
 *
 * <p>jq-ref 1.8.2 (observed 2026-11-13, the filter read with -nc -f): {@code .a+…} runs 6 554 terms and {@code .a and
 * …} and {@code .a or …} 3 277, {@code .a == .a and …} and {@code .a // …} 2 185; one more term is its bytecode limit
 * {@code function compiled to … bytes which is too long}, rc 3, as are 20 000 terms of each. 20 000 terms of {@code
 * .a // …} are its {@code memory exhausted}, rc 3, and 50 000 of {@code .a+} crash it (rc 139). {@code 1+1+…} folds:
 * 20 000 and 200 000 ones print their sum, rc 0, and so do they with a last {@code +.a}, and one more with a last
 * {@code +.a+1}.
 *
 * <p>As in IrDepthTest, the 512 KiB edges are pinned in one fresh child JVM with no agent, the case a host meets, since
 * an instrumented or warmed JVM needs more stack per level (F-44); this JVM pins the same filters on a 16 MiB thread,
 * with chains long enough that a frame per term would overflow it.
 */
class OperatorChainTest {

  /** ADR-0017's default stack for a run. */
  private static final long STACK = 512 * 1024;

  /** A stack the Resolver's 512 levels fit in whatever this JVM's state and instrumentation. */
  private static final long LARGE = 16L * 1024 * 1024;

  /** Terms of the child JVM's chains: past jq-ref's bytecode limit for every operator here. */
  private static final int CHILD_TERMS = 20_000;

  /** Terms of this JVM's chains: a Java frame or more per term would overflow {@link #LARGE}. */
  private static final int TERMS = 200_000;

  /** What {@link Child} prints for its chains, in its order. */
  private static final String EDGES =
      "20000 terms of .a+: a Stub\n"
          + "20000 terms of .a and: a Stub\n"
          + "20000 terms of .a or: a Stub\n"
          + "20000 terms of .a == .a and: a Stub\n"
          + "20000 terms of 1+: 20000\n"
          + "20000 terms of 1+ and a last +.a: 20000\n"
          + "20000 terms of .a //: a compile error\n";

  @TempDir Path dir;

  /**
   * One child JVM, started with the test class path, no agent and no tool or JVM options, runs {@link Child}: the
   * stack claim of every test here.
   */
  @Test
  void onA512KiBThreadInAFreshJvmLongOperatorChainsAreStubsOrFold() throws IOException, InterruptedException {
    File out = dir.resolve("out").toFile();
    ProcessBuilder launch =
        new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(out);
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

  /** A chain past the IR bound is the bound's Stub, however long; its 512 KiB claim is pinned by the child JVM. */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {"'+' | .a", "' and ' | .a", "' or ' | .a", "' and ' | .a == .a", "'-' | .a", "'*' | .a", "'/' | .a", "'%' | .a"})
  void aLongOperatorChainIsAStub(String operator, String term) throws InterruptedException {
    String filter = terms(TERMS, operator, term);
    Throwable thrown = on(LARGE, () -> Inlining.ENGINE.compile(filter));
    assertThat(thrown).as("what compiling %s threw on a 16 MiB thread", shown(filter)).isInstanceOf(JqCompileException.class);
    assertThat(thrown.getMessage()).as("message of the compile error of %s", shown(filter)).startsWith(MARK);
  }

  /**
   * A chain of constants folds to one Const, as jq's parser folds it (jq-ref 1.8.2 prints the sum), and a chain whose
   * last term is no constant lowers to its folded left operand plus that term.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {"'' | 200000", "'+.a' | 200000", "'+.a+1' | 200001"})
  void aLongChainOfConstantsFolds(String last, String result) throws InterruptedException {
    String filter = terms(TERMS, "+", "1") + last;
    AtomicReference<List<String>> got = new AtomicReference<>();
    Throwable thrown = on(LARGE, () -> got.set(results(filter)));
    assertThat(thrown).as("what running %s threw on a 16 MiB thread", shown(filter)).isNull();
    assertThat(got.get()).as("results of %s on null", shown(filter)).isEqualTo(List.of(result));
  }

  /** {@code n} copies of {@code term} joined by {@code separator}. */
  private static String terms(int n, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = 0; i < n; i++) {
      joined.add(term);
    }
    return joined.toString();
  }

  /**
   * The child JVM of {@link #onA512KiBThreadInAFreshJvmLongOperatorChainsAreStubsOrFold}: on a 512 KiB thread each,
   * it compiles and runs on null each chain of {@link #CHILD_TERMS} terms and prints a line for each.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      run(".a+", terms(CHILD_TERMS, "+", ".a"));
      run(".a and", terms(CHILD_TERMS, " and ", ".a"));
      run(".a or", terms(CHILD_TERMS, " or ", ".a"));
      run(".a == .a and", terms(CHILD_TERMS, " and ", ".a == .a"));
      run("1+", terms(CHILD_TERMS, "+", "1"));
      run("1+ and a last +.a", terms(CHILD_TERMS, "+", "1") + "+.a");
      run(".a //", terms(CHILD_TERMS, " // ", ".a"));
    }

    private static void run(String name, String filter) throws InterruptedException {
      AtomicReference<List<String>> got = new AtomicReference<>();
      Throwable thrown = on(STACK, () -> got.set(results(filter)));
      String shown;
      if (thrown == null) {
        shown = String.join(", ", got.get());
      } else if (thrown instanceof JqCompileException) {
        shown = thrown.getMessage().startsWith(MARK) ? "a Stub" : "a compile error";
      } else {
        shown = thrown.toString();
      }
      System.out.println(CHILD_TERMS + " terms of " + name + ": " + shown);
    }
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
