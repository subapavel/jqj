package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0043: a wide construct ticks exactly as its nested form does. Each run of n terms ends under the fewest steps
 * {@code a + b·n}, the line of its nested form, at n = 63 and 65, either side of {@code BIND_RUN} (64), and at 20 000,
 * past jq's own limit and far past the F-44 bound under which the nested form was built. The fewest steps are found by
 * bisection on the step budget, and each input is a view, which charges the entries a step reads (ADR-0034), so every
 * read of a term ticks.
 *
 * <p>The lines were observed at 2fe9654 (#75's merge, before #86, #92 and #94), in a scratch worktree, by the fewest
 * steps at n = 30 and n = 60 of each row below on its input (.logs/94-probe-2fe9654.log of the test-author's run); the
 * same probe gave the line's value at n = 63 and 65 there too. A read of {@code .a} is one step, an operator, {@code
 * and}, {@code or} and {@code //} none, and a Comma's right one; so a run of {@code .a} is {@code 1 + n}, one whose
 * terms are comparisons {@code 1 + 2·n}, one of variables {@code 2} whatever n, and one with a generator as the top
 * operator's right operand, or as the first term of an {@code or}, {@code 2 + 2·n}, since every other term runs once
 * for each of its two results.
 */
class WideConstructStepsTest {

  /** One run: its name, the filter of {@code n} terms, its view input, and its nested form's line {@code a + b·n}. */
  record Run(String name, String prefix, String separator, String term, String first, String last, String input, long a, long b) {
    String filter(int n) {
      StringJoiner joined = new StringJoiner(separator);
      for (int i = 0; i < n; i++) {
        joined.add(i == 0 && first != null ? first : i == n - 1 && last != null ? last : term);
      }
      return prefix + joined;
    }
  }

  /** Observed at 2fe9654: n = 30 and n = 60 give a + 30·b and a + 60·b. */
  private static final List<Run> RUNS =
      List.of(
          new Run(".a+", "", "+", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a-", "", "-", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a*", "", "*", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a/", "", "/", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run(".a%", "", "%", ".a", null, null, "{\"a\":1}", 1, 1),
          new Run("$x+", ".a as $x | ", "+", "$x", null, null, "{\"a\":1}", 2, 0),
          new Run(".a+, the last term (.a,.a)", "", "+", ".a", null, "(.a,.a)", "{\"a\":1}", 2, 2),
          new Run(".a and", "", " and ", ".a", null, null, "{\"a\":true}", 1, 1),
          new Run(".a or", "", " or ", ".a", null, null, "{\"a\":false}", 1, 1),
          new Run(".a or, the first term (.a,.a)", "", " or ", ".a", "(.a,.a)", null, "{\"a\":false}", 2, 2),
          new Run(".a == .a and", "", " and ", ".a == .a", null, null, "{\"a\":1}", 1, 2),
          new Run(".a //", "", " // ", ".a", null, null, "{\"a\":false}", 1, 1));

  static Stream<Arguments> runs() {
    List<Arguments> cases = new ArrayList<>();
    for (Run run : RUNS) {
      for (int n : new int[] {63, 65, 20_000}) {
        cases.add(Arguments.argumentSet(n + " terms of " + run.name() + " on " + run.input(), run, n));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("runs")
  void anOperatorRunTicksAsTheNestedFormDoes(Run run, int n) {
    String filter = run.filter(n);
    byte[] input = run.input().getBytes(StandardCharsets.UTF_8);
    assertThat(fewestSteps(filter, input)).as("fewest steps of %s on %s, its nested form's %d + %d·%d", Inlining.shown(filter), run.input(), run.a(), run.b(), n).isEqualTo(run.a() + run.b() * n);
  }

  /** The fewest steps a run of {@code filter} on the JSON text {@code input}, read as a view, ends under, by bisection. */
  private static long fewestSteps(String filter, byte[] input) {
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
