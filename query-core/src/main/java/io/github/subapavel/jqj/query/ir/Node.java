package io.github.subapavel.jqj.query.ir;

/** A node of the IR (ADR-0005). Each node keeps the part of the filter text it came from. */
public sealed interface Node permits Identity, Const, Pipe, Intrinsic {
  /** Where the node's text is in the filter. */
  Source source();
}
