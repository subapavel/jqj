package io.github.subapavel.jqj.query.compile;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Checks the rules of the IR (ADR-0005, ADR-0034) before a backend sees a tree; a broken rule is an
 * IllegalStateException. The tree uses no node object twice; a tree has one Bind per slot, and a Var reads its slot
 * only inside its Bind's body; an Intrinsic's arguments are atomic (Identity, Var or Const), as many as its call form's
 * arity: none for Fn0, one for Fn1, two for Fn2, and any number for an FnN or a Many, which take them as an array.
 */
final class IrVerifier {
  /** The nodes seen so far, compared by identity, not by equality. */
  private final Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());

  /** The slots a Bind of the tree took so far. */
  private final Set<Integer> bound = new HashSet<>();

  /** The slots whose Bind's body the walk is in. */
  private final Set<Integer> scope = new HashSet<>();

  /** One more than the largest slot so far. */
  private int slots;

  private IrVerifier() {}

  /** Checks {@code root}; returns the number of slots a run of it needs, one more than its largest slot. */
  static int verify(Node root) {
    IrVerifier verifier = new IrVerifier();
    verifier.visit(root);
    return verifier.slots;
  }

  private void visit(Node node) {
    if (!seen.add(node)) {
      throw new IllegalStateException("the node at bytes " + node.source() + " is used twice: the IR must be a tree");
    }
    // One arm per kind: javac compiles a case of two patterns to a second dispatch for a node that matches neither,
    // a path no node can take.
    switch (node) {
      case Identity _ -> {
        // No operand node.
      }
      case Const _ -> {
        // A leaf: its value is no node.
      }
      case Var v -> {
        if (!scope.contains(v.slot())) {
          throw new IllegalStateException("the Var of slot " + v.slot() + " at bytes " + v.source() + " is outside the body of its Bind");
        }
      }
      case Intrinsic i -> arguments(i);
      case Pipe p -> {
        visit(p.left());
        visit(p.right());
      }
      case Comma c -> {
        visit(c.left());
        visit(c.right());
      }
      case Collect c -> visit(c.body());
      case Bind b -> {
        if (!bound.add(b.slot())) {
          throw new IllegalStateException("the slot " + b.slot() + " of the Bind at bytes " + b.source() + " is bound twice in the tree");
        }
        slots = Math.max(slots, b.slot() + 1);
        // The value runs before the slot is set, so only the body may read it.
        visit(b.value());
        scope.add(b.slot());
        visit(b.body());
        scope.remove(b.slot());
      }
    }
  }

  private void arguments(Intrinsic i) {
    int arity =
        switch (i.op().form()) {
          case Fn0 _ -> 0;
          case Fn1 _ -> 1;
          case Fn2 _ -> 2;
          case FnN _ -> i.args().size();
          case Many _ -> i.args().size();
        };
    if (i.args().size() != arity) {
      throw new IllegalStateException(
          "the Intrinsic " + i.op().name() + " at bytes " + i.source() + " has " + i.args().size() + " arguments; its form takes " + arity);
    }
    for (Node a : i.args()) {
      if (!(a instanceof Identity || a instanceof Var || a instanceof Const)) {
        throw new IllegalStateException("an argument of the Intrinsic " + i.op().name() + " at bytes " + i.source() + " is not atomic: " + a);
      }
      visit(a);
    }
  }
}
