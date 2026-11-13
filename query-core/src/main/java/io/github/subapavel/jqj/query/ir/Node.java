package io.github.subapavel.jqj.query.ir;

/** A node of the IR (ADR-0005, ADR-0034). Each node keeps the part of the filter text it came from. */
public sealed interface Node permits Identity, Const, Pipe, Comma, Bind, Var, Intrinsic, Collect {
  /** Where the node's text is in the filter. */
  Source source();
}
