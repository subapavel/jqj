package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.ConstOp;
import io.github.subapavel.jqj.query.internal.interpret.IdentityOp;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicOp;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.PipeOp;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;

/** Compiles an IR tree: verifies it, then builds the interpreter tree (ADR-0018). There is no analysis yet. */
public final class Compiler {
  private Compiler() {}

  /**
   * As {@link #compile(Node, CompileMode)} in the optimised compile.
   *
   * @throws IllegalStateException when the tree breaks a rule of the IR
   */
  public static Executable compile(Node root) {
    return compile(root, CompileMode.OPTIMISED);
  }

  /**
   * Compiles {@code root} in {@code mode}. There is no analysis yet, so both modes build the same tree (ADR-0030).
   *
   * @throws IllegalStateException when the tree breaks a rule of the IR
   */
  public static Executable compile(Node root, CompileMode mode) {
    IrVerifier.verify(root);
    return new Executable(interpreter(root));
  }

  private static Op interpreter(Node node) {
    return switch (node) {
      case Identity _ -> new IdentityOp();
      case Const c -> new ConstOp(c.value());
      case Pipe p -> new PipeOp(interpreter(p.left()), interpreter(p.right()));
      case Intrinsic i -> new IntrinsicOp(i.op().fn());
    };
  }
}
