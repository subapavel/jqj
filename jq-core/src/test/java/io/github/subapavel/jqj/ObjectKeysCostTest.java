package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0043 (#93): a site whose keys are all constant strings finds a repeated key through a map by its bytes, not by a
 * scan of the distinct keys before it ({@code Resolver.shaped}), so an object of n distinct constant keys compiles in
 * about n key lookups and not n²/2 compares: 1.25·10⁹ at {@link #KEYS} keys.
 *
 * <p>No seam counts the Resolver's key compares, so the bound is wall time, taken in a fresh child JVM with no agent
 * and no tool or JVM options, as IrDepthTest's children are, and generous: {@link #BOUND_MS}. The witness is the same
 * width with one computed key in front, a site that passes a key and a value per pair and never looks for a repeated
 * key, which compiles under the same bound before #93; so the bound does not stop {@link #KEYS} pairs, only the scan.
 * jq-ref 1.8.2 does not compile either filter (one past 4998 keys without values is "memory exhausted", .logs/
 * 93-jqref-limits.log of the test-author's run): only the compile's time is pinned here, the results of a repeated
 * constant key by ConstructionOrderTest.
 */
class ObjectKeysCostTest {

  /** Distinct constant keys of the object. */
  static final int KEYS = 50_000;

  /** The most milliseconds the compile of either filter may take. */
  static final long BOUND_MS = 10_000;

  @TempDir Path dir;

  @Test
  void anObjectOf50000ConstantKeysCompilesWithoutAScanOfTheKeysBeforeEach() throws IOException, InterruptedException {
    String printed = runChild();
    String[] lines = printed.split("\n");
    assertThat(lines).as("lines the child JVM printed: %s", printed).hasSize(2);
    assertThat(Long.parseLong(lines[0])).as("milliseconds to compile %d pairs after one computed key, the witness; printed %s", KEYS, printed).isLessThan(BOUND_MS);
    assertThat(Long.parseLong(lines[1])).as("milliseconds to compile %d constant keys, the witness's %s", KEYS, lines[0]).isLessThan(BOUND_MS);
  }

  /** What {@link Child} printed, run in a fresh JVM, once it ended within 600 s with exit code 0. */
  private String runChild() throws IOException, InterruptedException {
    File out = dir.resolve("out").toFile();
    List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName());
    ProcessBuilder launch = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out);
    // Each would reach the child's command line, and an agent there would slow the compile it times.
    launch.environment().remove("JAVA_TOOL_OPTIONS");
    launch.environment().remove("JDK_JAVA_OPTIONS");
    launch.environment().remove("_JAVA_OPTIONS");
    Process child = launch.start();
    try {
      assertThat(child.waitFor(600, TimeUnit.SECONDS)).as("the child JVM ends within 600 s").isTrue();
      String printed = Files.readString(out.toPath(), UTF_8);
      assertThat(child.exitValue()).as("the child JVM's exit code; it printed %s", printed).isZero();
      return printed;
    } finally {
      child.destroyForcibly();
    }
  }

  /**
   * The child JVM: it compiles the witness, {@code {(.k): 0, k1: 0, …}}, then {@code {k0: 0, k1: 0, …}}, each of
   * {@link #KEYS} pairs, and prints the milliseconds each compile took, a line each.
   */
  static final class Child {
    private Child() {}

    public static void main(String[] args) {
      StringJoiner rest = new StringJoiner(", ");
      for (int i = 1; i < KEYS; i++) {
        rest.add("k" + i + ": 0");
      }
      System.out.println(millisToCompile("{(.k): 0, " + rest + "}"));
      System.out.println(millisToCompile("{k0: 0, " + rest + "}"));
    }

    private static long millisToCompile(String filter) {
      long start = System.nanoTime();
      Inlining.ENGINE.compile(filter);
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
  }
}
