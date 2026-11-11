package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.IdentityOp;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Node;

/** Compiles an IR tree: verifies it, then builds the interpreter tree (ADR-0018). There is no analysis yet. */
public final class Compiler {
  private Compiler() {}

  /** @throws IllegalStateException when the tree breaks a rule of the IR */
  public static Executable compile(Node root) {
    IrVerifier.verify(root);
    return new Executable(interpreter(root));
  }

  private static Op interpreter(Node node) {
    return switch (node) {
      case Identity _ -> new IdentityOp();
    };
  }
}
