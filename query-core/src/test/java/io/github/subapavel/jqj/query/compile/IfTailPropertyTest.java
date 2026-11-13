package io.github.subapavel.jqj.query.compile;

import static io.github.subapavel.jqj.query.compile.RandomParts.NO;
import static io.github.subapavel.jqj.query.compile.RandomParts.ROOT;
import static io.github.subapavel.jqj.query.compile.RandomParts.TRUTH;
import static io.github.subapavel.jqj.query.compile.RandomParts.outcome;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.RandomParts.Both;
import io.github.subapavel.jqj.query.compile.RandomParts.Cond;
import io.github.subapavel.jqj.query.compile.RandomParts.Fence;
import io.github.subapavel.jqj.query.compile.RandomParts.Form;
import io.github.subapavel.jqj.query.compile.RandomParts.Generator;
import io.github.subapavel.jqj.query.compile.RandomParts.Let;
import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.compile.RandomParts.Part;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * IfOp takes a tail condition's result before it runs the branch (ADR-0043): a tail emitter gives one result at most
 * and never breaks out, so the branch may run after the condition returned instead of inside its emit. That changes
 * how deep the run goes and nothing else: the same results in the same order, the same error or termination, the same
 * answer to a sink that stops, and the same steps and bytes built.
 *
 * <p>The seeded property (docs/agents/environment.md, one case per seed) builds {@code label $root | (c1, c2) as $o |
 * if C then T else E end}, C a random tail emitter of {@link RandomParts}, a compound one in three seeds of four, and T
 * and E random parts of any kind (a Comma, a raising or an empty part, a Many, a Collect whose body breaks, or a
 * compound). Its push form, the oracle, is the same tree with each If's condition inside a Label no Break names and
 * each Binds as nested Bind nodes ({@link RandomParts}). The seed also picks the step budget and the result at which
 * the sink stops, and is the case's name and in every description.
 *
 * <p>The depth: a run of {@code and} or {@code or} nests in an If's condition (ADR-0037's lowering, ADR-0043 builds a
 * long run balanced), and in push form each term's emit stays on the stack while every later term runs. Taken first,
 * the balanced run of {@value #TERMS} terms runs on a 512 KiB thread in a fresh child JVM ({@link FreshJvm}).
 */
class IfTailPropertyTest {
  private static final Source AT = new Source(0, 1);

  /** The terms of each balanced run. */
  static final int TERMS = 20_000;

  /** The toy {@code true}; the toy {@code false} is {@link RandomParts#NO}. */
  private static final Object YES = "yes";

  @TempDir Path dir;

  static LongStream seeds() {
    return LongStream.range(0, 400);
  }

  /** The root of one seed, the first slot its parts left free, its step budget and the result its sink stops at. */
  record Case(Part root, int free, long steps, int stopAt) {}

  static Case generate(long seed) {
    Generator g = new Generator(seed);
    int outer = g.fresh();
    List<Integer> scope = List.of(outer);
    Part cond = g.below(4) == 0 ? g.tail(3, scope) : g.compound(3, scope);
    Part main = new Let(new Both(g.constant(), g.constant()), outer, new Cond(cond, g.part(scope), g.part(scope)));
    long steps = g.below(3) == 0 ? g.below(8) : Long.MAX_VALUE;
    int stopAt = g.below(3) == 0 ? Integer.MAX_VALUE : 1 + g.below(6);
    return new Case(new Fence(ROOT, main), g.free(), steps, stopAt);
  }

  /** The outcome of {@code c} with its If on its tail condition, in {@code mode}. */
  private static Outcome tail(Case c, CompileMode mode) {
    return outcome(c.root().build(Form.tail(c.free())), mode, c.steps(), c.stopAt());
  }

  /** The outcome of the push form of {@code c} in {@code mode}: the oracle. */
  private static Outcome push(Case c, CompileMode mode) {
    return outcome(c.root().build(Form.push(c.free())), mode, c.steps(), c.stopAt());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aTailConditionRunsAsItsPushFormDoes(long seed) {
    Case c = generate(seed);
    for (CompileMode mode : CompileMode.values()) {
      Outcome push = push(c, mode);
      assertThat(push.crashed()).as("seed %d, %s: whether the push form crashed (%s) on %s", seed, mode, push, c).isFalse();
      assertThat(tail(c, mode)).as("seed %d, %s: outcome of the If on its tail condition in %s", seed, mode, c).isEqualTo(push);
    }
  }

  /**
   * Witness that the property is not vacuous: over the seeds, the If on its tail condition runs out with two results
   * or more, runs out with none, stops at the sink, raises and ends at the step budget, each in 5 % of the seeds at
   * least, and no seed crashes. (At #92's red the push form of the 400 seeds gave 150, 65, 80, 66 and 36.)
   */
  @Test
  void theSeedsReachEveryOutcome() {
    List<Outcome> outcomes = new ArrayList<>();
    seeds().forEach(seed -> outcomes.add(tail(generate(seed), CompileMode.OPTIMISED)));
    Map<String, Integer> kinds = RandomParts.kinds(outcomes);
    int least = outcomes.size() / 20;
    assertThat(kinds).as("outcomes of the tail form by kind over %d seeds", outcomes.size()).doesNotContainKey("crash");
    for (String kind : List.of("results", "none", "stop", "error", "termination")) {
      assertThat(kinds.getOrDefault(kind, 0)).as("seeds whose tail form ends in %s, of %s", kind, kinds).isGreaterThanOrEqualTo(least);
    }
  }

  /**
   * {@code a and b} is {@code if a then (if b then true else false) else false} and {@code a or b} is {@code if a then
   * true else (if b then true else false)} (ADR-0037), here over balanced halves. Every term true gives true, the last
   * term false gives false, and every term false gives false for {@code or}; none of it ticks.
   */
  @Test
  void aBalancedRunOfTailConditionsRunsOnA512KiBThread() throws IOException, InterruptedException {
    assertThat(FreshJvm.printed(dir, Child.class, "balanced"))
        .as("what the child JVM printed")
        .isEqualTo(
            "20000 and-terms: [yes] in 0 steps\n"
                + "20000 and-terms, the last one false: [no] in 0 steps\n"
                + "20000 or-terms, all false: [no] in 0 steps\n");
  }

  /** The child JVM of {@link #aBalancedRunOfTailConditionsRunsOnA512KiBThread}: each run on a 512 KiB thread. */
  static final class Child {
    private Child() {}

    public static void main(String[] args) throws InterruptedException {
      FreshJvm.print(TERMS + " and-terms", () -> ran(and(0, TERMS, -1)));
      FreshJvm.print(TERMS + " and-terms, the last one false", () -> ran(and(0, TERMS, TERMS - 1)));
      FreshJvm.print(TERMS + " or-terms, all false", () -> ran(or(0, TERMS)));
    }
  }

  /**
   * The results of {@code run}, compiled here, inside {@code yes as $0 | no as $1 | …}, with no budget and a sink that
   * never stops, and its steps.
   */
  private static String ran(Node run) {
    Node root = new Bind(AT, new Const(AT, YES), 0, new Bind(AT, new Const(AT, NO), 1, run));
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    List<Object> results = new ArrayList<>();
    boolean ranOut =
        Compiler.compile(root, CompileMode.OPTIMISED)
            .run(
                state,
                "in",
                v -> {
                  results.add(v);
                  return true;
                });
    return (ranOut ? "" : "stopped: ") + results + " in " + state.steps() + " steps";
  }

  /** The terms {@code [from, to)} joined by {@code and}, balanced; term {@code no} is false, every other one true. */
  private static Node and(int from, int to, int no) {
    if (to - from == 1) {
      return term(from, from == no ? NO : YES);
    }
    int middle = (from + to) >>> 1;
    return new If(AT, and(from, middle, no), new If(AT, and(middle, to, no), new Const(AT, YES), new Const(AT, NO), TRUTH), new Const(AT, NO), TRUTH);
  }

  /** The terms {@code [from, to)}, every one false, joined by {@code or}, balanced. */
  private static Node or(int from, int to) {
    if (to - from == 1) {
      return term(from, NO);
    }
    int middle = (from + to) >>> 1;
    return new If(AT, or(from, middle), new Const(AT, YES), new If(AT, or(middle, to), new Const(AT, YES), new Const(AT, NO), TRUTH), TRUTH);
  }

  /** Term {@code k} giving {@code value}: a Const, a Var of slot 0 or 1 (yes or no), or an Fn0, by k mod 3. */
  private static Node term(int k, Object value) {
    return switch (k % 3) {
      case 0 -> new Const(AT, value);
      case 1 -> new Var(AT, YES.equals(value) ? 0 : 1);
      default -> new Intrinsic(AT, new Operation("term", (Fn0) (state, in) -> value), List.of());
    };
  }
}
