package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Node;

/** Checks the rules of the IR (ADR-0005) before a backend sees a tree; a broken rule is an IllegalStateException. */
final class IrVerifier {
  private IrVerifier() {}

  static void verify(Node node) {
    switch (node) {
      case Identity _ -> {
        // The identity has no operand, so it has no rule to break.
      }
    }
  }
}
