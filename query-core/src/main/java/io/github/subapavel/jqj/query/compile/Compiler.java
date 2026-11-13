package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.Argument;
import io.github.subapavel.jqj.query.internal.interpret.BindOp;
import io.github.subapavel.jqj.query.internal.interpret.CollectOp;
import io.github.subapavel.jqj.query.internal.interpret.CommaOp;
import io.github.subapavel.jqj.query.internal.interpret.ConstOp;
import io.github.subapavel.jqj.query.internal.interpret.IdentityOp;
import io.github.subapavel.jqj.query.internal.interpret.Intrinsic1Op;
import io.github.subapavel.jqj.query.internal.interpret.Intrinsic2Op;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicManyOp;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicNOp;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicOp;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.PipeOp;
import io.github.subapavel.jqj.query.internal.interpret.VarOp;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.Many;
import java.util.List;

/** Compiles an IR tree: verifies it, then builds the interpreter tree (ADR-0018). There is no analysis yet. */
public final class Compiler {
  private Compiler() {}

  /**
   * Compiles {@code root} in {@code mode}. There is no analysis yet, so both modes build the same tree (ADR-0030).
   *
   * @throws IllegalStateException when the tree breaks a rule of the IR
   */
  public static Executable compile(Node root, CompileMode mode) {
    int slots = IrVerifier.verify(root);
    return new Executable(interpreter(root), slots);
  }

  private static Op interpreter(Node node) {
    return switch (node) {
      case Identity _ -> new IdentityOp();
      case Const c -> new ConstOp(c.value());
      case Pipe p -> new PipeOp(interpreter(p.left()), interpreter(p.right()));
      case Comma c -> new CommaOp(interpreter(c.left()), interpreter(c.right()));
      case Bind b -> new BindOp(interpreter(b.value()), b.slot(), interpreter(b.body()));
      case Var v -> new VarOp(v.slot());
      case Intrinsic i -> intrinsic(i);
      case Collect c -> new CollectOp(interpreter(c.body()), c.collector());
    };
  }

  /** The call of {@code i} in its form, with the arity and the atomic arguments the verifier checked. */
  private static Op intrinsic(Intrinsic i) {
    List<Node> nodes = i.args();
    Argument[] args = new Argument[nodes.size()];
    for (int k = 0; k < args.length; k++) {
      args[k] = argument(nodes.get(k));
    }
    return switch (i.op().form()) {
      case Fn0 fn -> new IntrinsicOp(fn);
      case Fn1 fn -> new Intrinsic1Op(fn, args[0]);
      case Fn2 fn -> new Intrinsic2Op(fn, args[0], args[1]);
      case FnN fn -> new IntrinsicNOp(fn, args);
      case Many many -> new IntrinsicManyOp(many, args);
    };
  }

  /** An atomic argument: a Var's slot, a Const's value, or else an Identity's input, the only other kind the verifier lets through. */
  private static Argument argument(Node a) {
    return switch (a) {
      case Var v -> Argument.slot(v.slot());
      case Const c -> Argument.constant(c.value());
      default -> Argument.input();
    };
  }
}
