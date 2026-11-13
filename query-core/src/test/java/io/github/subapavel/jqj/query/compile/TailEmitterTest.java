package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Binds is a tail emitter only when its values and its body all are (ADR-0043), over plain strings (founding §14): a
 * Binds of tail values whose body is a Comma gives two results, so as a Binds value or an If condition it runs the rest
 * once per result, as the nested chain does, in both compile modes.
 */
class TailEmitterTest {
  private static final Source AT = new Source(0, 1);

  private static final Truth ALL = v -> true;

  /** {@code "a" as $1 | "b" as $2 | ($1, $2)}: tail values, a generator body. */
  private static Binds pair(int first, int second) {
    return new Binds(AT, List.of(new Const(AT, "a"), new Const(AT, "b")), List.of(first, second), new Comma(AT, new Var(AT, first), new Var(AT, second)));
  }

  private static Intrinsic list(Node... args) {
    FnN all = (state, in, a) -> List.of(a);
    return new Intrinsic(AT, new Operation("list", all), List.of(args));
  }

  private static List<Object> results(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    Compiler.compile(root, mode)
        .run(
            new RunState(),
            "in",
            v -> {
              emitted.add(v);
              return true;
            });
    return emitted;
  }

  @Test
  void aBindsWhoseBodyIsAGeneratorRunsTheRestPerResult() {
    // (pair) as $0 | "c" as $3 | list($0; $3)
    for (CompileMode mode : CompileMode.values()) {
      Binds value = new Binds(AT, List.of(pair(1, 2), new Const(AT, "c")), List.of(0, 3), list(new Var(AT, 0), new Var(AT, 3)));
      assertThat(results(value, mode)).as("%s: results of a Binds whose first value is the pair", mode).containsExactly(List.of("a", "c"), List.of("b", "c"));
      // if (pair) then list(.) else "no" end
      If cond = new If(AT, pair(1, 2), list(new Const(AT, "then")), new Const(AT, "no"), ALL);
      assertThat(results(cond, mode)).as("%s: results of an If on the pair", mode).containsExactly(List.of("then"), List.of("then"));
    }
  }
}
