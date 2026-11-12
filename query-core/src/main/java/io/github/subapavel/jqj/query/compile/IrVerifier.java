package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Checks the rules of the IR (ADR-0005) before a backend sees a tree; a broken rule is an IllegalStateException. */
final class IrVerifier {
  private IrVerifier() {}

  static void verify(Node root) {
    visit(root, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  /** The tree rule: no node object is used twice, so {@code seen} compares nodes by identity, not by equality. */
  private static void visit(Node node, Set<Node> seen) {
    if (!seen.add(node)) {
      throw new IllegalStateException("the node at chars " + node.source() + " is used twice: the IR must be a tree");
    }
    // One arm per kind: javac compiles a case of two patterns to a second dispatch for a node that matches neither,
    // a path no node can take.
    switch (node) {
      case Identity _ -> {
        // No operand node.
      }
      case Intrinsic _ -> {
        // No operand node.
      }
      case Pipe p -> {
        visit(p.left(), seen);
        visit(p.right(), seen);
      }
    }
  }
}
