package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0043 (#93): past {@code BIND_RUN} (= {@code PIPE_CHAIN}, 64) nested Binds an object, an interpolation, a pattern
 * and a value parameter's expansion lower to one Binds, which ticks and charges nothing of its own, and whose values
 * run in the order the nested form runs them. So such a construct ticks as its nested form does: the fewest steps a
 * run of n parts ends under lie on the line {@code a + b·n} the nested form draws, below the run (n = 63, nested at
 * every commit) and past it (n = 65 and 20 000, one Binds once #93 lowers it, and 20 000 a Stub before).
 *
 * <p>{@code a} and {@code b} were observed at 2fe9654 (before the Binds node of #92 and the lowering of #93, every
 * construct a chain of nested Binds), in a scratch copy of that commit running {@link #fewestSteps} on {@link #filter}
 * and the row's input with n = 30 and n = 60: {@code b = (s60 − s30) / 30} and {@code a = s30 − 30·b}, each row's
 * observed pair in its comment in {@link #constructs}. Each input is JSON text read as a view, which charges the entries
 * a step reads (ADR-0034), so every part ticks: a field read one tick per key of the object, an index one per element
 * stepped over. No part ticks by the KiB: the interpolated value is the empty string.
 */
class WideConstructStepsTest {

  /**
   * One row per construct of ADR-0043's lowering: its name ({@link #filter}), the input, and the line's {@code a} and
   * {@code b} from 2fe9654.
   */
  static Stream<Arguments> constructs() {
    return Stream.of(
        // 2fe9654: s30 = 91, s60 = 181.
        Arguments.of("object of computed values", "{\"b\":0,\"c\":0,\"a\":0}", 1L, 3L),
        // 2fe9654: s30 = 181, s60 = 361.
        Arguments.of("object of computed keys", "{\"b\":0,\"k\":\"x\",\"a\":0}", 1L, 6L),
        // 2fe9654: s30 = 61, s60 = 121.
        Arguments.of("interpolation", "{\"b\":0,\"a\":\"\"}", 1L, 2L),
        // 2fe9654: s30 = 85, s60 = 175.
        Arguments.of("array pattern", "[0,0,0]", -5L, 3L),
        // 2fe9654: s30 = 121, s60 = 241.
        Arguments.of("object pattern of computed keys", "{\"k\":\"x\",\"b\":0}", 1L, 4L),
        // 2fe9654: s30 = 86, s60 = 176.
        Arguments.of("alternatives", "[0,0,0]", -4L, 3L),
        // 2fe9654: s30 = 61, s60 = 121.
        Arguments.of("value parameters", "{\"b\":0,\"a\":0}", 1L, 2L));
  }

  /**
   * The row's construct of {@code n} parts: an object of n constant keys with a field read each; of n pairs whose key
   * and value are field reads, the key checked; an interpolation of n field reads; an array pattern of n variables; an
   * object pattern of n computed keys, each a key step and an index step; a {@code ?//} whose first alternative is an
   * array pattern of n elements, the first {@code {$z}}, which raises on the input's 0 after every other element is
   * bound, so the second runs; and the expansion of a definition of n value parameters, each argument a field read.
   */
  static String filter(String construct, int n) {
    return switch (construct) {
      case "object of computed values" -> "{" + terms(0, n, ", ", "k%d: .a") + "}";
      case "object of computed keys" -> "{" + terms(0, n, ", ", "(.k): .a") + "}";
      case "interpolation" -> "\"" + "\\(.a)".repeat(n) + "\"";
      case "array pattern" -> ". as [" + terms(0, n, ", ", "$a%d") + "] | 1";
      case "object pattern of computed keys" -> ". as {" + terms(0, n, ", ", "(.k): $a%d") + "} | 1";
      case "alternatives" -> ". as [{$z}, " + terms(1, n, ", ", "$a%d") + "] ?// $a1 | 1";
      case "value parameters" -> "def f(" + terms(0, n, "; ", "$a%d") + "): 1; f(" + terms(0, n, "; ", ".a") + ")";
      default -> throw new IllegalArgumentException(construct);
    };
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constructs")
  void aWideConstructTicksAsTheNestedFormDoes(String construct, String input, long a, long b) {
    byte[] json = input.getBytes(UTF_8);
    for (int n : new int[] {63, 65, 20_000}) {
      String filter = filter(construct, n);
      assertThatCode(() -> Inlining.ENGINE.compile(filter)).as("compile of the %s of %d parts", construct, n).doesNotThrowAnyException();
      assertThat(fewestSteps(filter, json)).as("fewest steps of the %s of %d parts on %s, against %d + %d·%d", construct, n, input, a, b, n).isEqualTo(a + b * n);
    }
  }

  /** The copies {@code from .. to - 1} of {@code term}, each {@code %d} in it the copy's number, joined by {@code separator}. */
  private static String terms(int from, int to, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = from; i < to; i++) {
      joined.add(term.replace("%d", Integer.toString(i)));
    }
    return joined.toString();
  }

  /** The fewest steps a run of {@code filter} on the JSON text {@code input}, read as a view, ends under, by bisection. */
  static long fewestSteps(String filter, byte[] input) {
    long low = 1;
    long high = 1L << 24;
    while (low < high) {
      long mid = (low + high) >>> 1;
      if (endsUnder(filter, input, mid)) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return low;
  }

  private static boolean endsUnder(String filter, byte[] input, long steps) {
    JqEngine engine = RunBudgets.steps(steps);
    Workspace workspace = engine.newWorkspace();
    try {
      engine.compile(filter).run(RunBudgets.read(workspace, input).root(), RunBudgets.NONE, v -> true, workspace);
      return true;
    } catch (JqTermination e) {
      return false;
    }
  }
}
