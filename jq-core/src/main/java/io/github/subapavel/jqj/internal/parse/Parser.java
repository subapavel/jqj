package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Source;

/** Parses a filter straight into IR (#33 design; an AST comes with resolution in spec 2). Only {@code .} is built. */
public final class Parser {
  private Parser() {}

  /** The IR of {@code text}: {@code .} with whitespace around it is the identity; any other text is a Stub. */
  public static Node parse(String text) {
    Lexer lexer = new Lexer(text);
    Token dot = lexer.next();
    if (dot.kind() != Token.Kind.DOT || lexer.next().kind() != Token.Kind.END) {
      throw Stub.unbuilt("a filter other than .");
    }
    return new Identity(new Source(dot.start(), dot.end()));
  }
}
