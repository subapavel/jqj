package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;

/**
 * Parses a filter straight into IR (#33 design; an AST comes with resolution in spec 2). The grammar built so far
 * (#34 design section 5):
 *
 * <pre>
 * pipe    := postfix ('|' pipe)?        nests right
 * postfix := primary FIELD*             nests left, as Pipe(term, Intrinsic)
 * primary := '.' | FIELD | '(' pipe ')' the identity, a bare Intrinsic, the inner node (no node for parentheses)
 * </pre>
 *
 * Any token sequence the grammar rejects is a Stub: the syntax errors come later.
 */
public final class Parser {
  private final String text;
  private final Lexer lexer;
  private Token token;

  private Parser(String text) {
    this.text = text;
    this.lexer = new Lexer(text);
    this.token = lexer.next();
  }

  /** The IR of {@code text}. */
  public static Node parse(String text) {
    Parser parser = new Parser(text);
    Node node = parser.pipe();
    parser.expect(Token.Kind.END);
    return node;
  }

  private Node pipe() {
    Node left = postfix();
    if (token.kind() != Token.Kind.PIPE) {
      return left;
    }
    advance();
    Node right = pipe();
    return new Pipe(span(left, right), left, right);
  }

  private Node postfix() {
    Node term = primary();
    while (token.kind() == Token.Kind.FIELD) {
      Node field = field(advance());
      term = new Pipe(span(term, field), term, field);
    }
    return term;
  }

  private Node primary() {
    return switch (token.kind()) {
      case DOT -> {
        Token dot = advance();
        yield new Identity(new Source(dot.start(), dot.end()));
      }
      case FIELD -> field(advance());
      case LPAREN -> {
        advance();
        Node inner = pipe();
        expect(Token.Kind.RPAREN);
        yield inner;
      }
      case PIPE, RPAREN, END -> throw unbuilt();
    };
  }

  /** {@code .name}: an Intrinsic over the name's operation, whose chars are the token's. */
  private Intrinsic field(Token t) {
    String name = text.substring(t.start() + 1, t.end());
    return new Intrinsic(new Source(t.start(), t.end()), new Operation("." + name, new Field(name)));
  }

  /** The chars from the start of {@code first} to the end of {@code last}. */
  private static Source span(Node first, Node last) {
    return new Source(first.source().start(), last.source().end());
  }

  /** Takes the current token and reads the next one. */
  private Token advance() {
    Token t = token;
    token = lexer.next();
    return t;
  }

  private void expect(Token.Kind kind) {
    if (token.kind() != kind) {
      throw unbuilt();
    }
    advance();
  }

  private RuntimeException unbuilt() {
    return Stub.unbuilt("the filter's token at char " + token.start());
  }
}
