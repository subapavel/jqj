package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.US_ASCII;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.TextFormat;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Parses a filter straight into IR (#33 design; an AST comes with resolution in spec 2). Every Source is in UTF-8 bytes
 * of the filter (ADR-0031). The grammar built so far (#34 design section 5, #61):
 *
 * <pre>
 * pipe    := postfix ('|' pipe)?                nests right
 * postfix := primary (FIELD | '.' string)*      nests left, as Pipe(term, Intrinsic)
 * primary := '.' string | '.' | FIELD           a field Intrinsic, the identity, a field Intrinsic
 *          | NUMBER | string                    a Const of a LiteralNumber or a BuiltString
 *          | true | false | null                IDENTs with no '(' after them: a Const
 *          | '@text'                            with no string after it: the @text Intrinsic
 *          | '(' pipe ')'                       the inner node (no node for parentheses)
 * string  := '"' (TEXT | escape run)* '"'       no interpolation (#67)
 * </pre>
 *
 * Any token sequence the grammar rejects is a Stub: the syntax errors come later. The lexical errors, one block per bad
 * escape run, are thrown together once the whole text has parsed, so a Stub met during the parse wins (ADR-0031).
 */
public final class Parser {
  /** The IDENTs that are constants (jq's parser), unless a {@code (} makes them a call. */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true", JqBool.TRUE, "false", JqBool.FALSE, "null", JqNull.NULL);

  private final Lexer lexer;

  /** The filter's UTF-8 bytes, which the tokens index. */
  private final byte[] b;

  private Token token;

  private Parser(String text) {
    this.lexer = new Lexer(text);
    this.b = lexer.bytes();
    this.token = lexer.next();
  }

  /** The IR of {@code text}. */
  public static Node parse(String text) {
    Parser parser = new Parser(text);
    Node node = parser.pipe();
    parser.expect(Token.Kind.END);
    parser.lexer.throwErrors();
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
    for (;; ) {
      Node index;
      if (token.kind() == Token.Kind.FIELD) {
        index = field(advance());
      } else if (token.kind() == Token.Kind.DOT) {
        index = stringField(advance());
      } else {
        return term;
      }
      term = new Pipe(span(term, index), term, index);
    }
  }

  private Node primary() {
    return switch (token.kind()) {
      case DOT -> {
        Token dot = advance();
        yield token.kind() == Token.Kind.STRING_START ? stringField(dot) : new Identity(source(dot));
      }
      case FIELD -> field(advance());
      case NUMBER -> {
        Token number = advance();
        yield new Const(source(number), LiteralNumber.ofLexeme(ascii(number.start(), number.end())));
      }
      case STRING_START -> string();
      case IDENT -> {
        JqValue value = CONSTANTS.get(ascii(token.start(), token.end()));
        if (value == null) {
          throw unbuilt();
        }
        Token name = advance();
        if (token.kind() == Token.Kind.LPAREN) {
          // A call of a function by that name.
          throw unbuilt();
        }
        yield new Const(source(name), value);
      }
      case FORMAT -> {
        Token format = advance();
        if (!ascii(format.start(), format.end()).equals("@text") || token.kind() == Token.Kind.STRING_START) {
          // Another format, or a format string.
          throw unbuilt();
        }
        yield new Intrinsic(source(format), new Operation("@text", new TextFormat()));
      }
      case LPAREN -> {
        advance();
        Node inner = pipe();
        expect(Token.Kind.RPAREN);
        yield inner;
      }
      default -> throw unbuilt();
    };
  }

  /** {@code .name}: an Intrinsic over the name's operation, whose bytes are the token's. */
  private Intrinsic field(Token t) {
    String name = ascii(t.start() + 1, t.end());
    return new Intrinsic(source(t), new Operation("." + name, new Field(name)));
  }

  /** {@code ."s"} after {@code dot}: an Intrinsic over the field of the string's value, from the dot to the quote. */
  private Intrinsic stringField(Token dot) {
    Const key = string();
    BuiltString name = (BuiltString) key.value();
    return new Intrinsic(new Source(dot.start(), key.source().end()), new Operation("." + name.toJson(), new Field(name.value())));
  }

  /**
   * A string literal: its TEXT runs' bytes and its escape runs' values joined, a Const of that BuiltString from quote to
   * quote. A bad escape run adds nothing: its error block ends the parse. An interpolation is a Stub (#67).
   */
  private Const string() {
    Token open = token;
    expect(Token.Kind.STRING_START);
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    for (;; ) {
      switch (token.kind()) {
        case TEXT -> value.write(b, token.start(), token.end() - token.start());
        case ESCAPES -> value.writeBytes(JsonStrings.decode(b, token.start(), token.end()));
        case INVALID_ESCAPES -> {
          // jq gives the run no value and goes on (its yylex); the lexer holds its error block.
        }
        case STRING_END -> {
          Token close = advance();
          return new Const(new Source(open.start(), close.end()), BuiltString.of(value.toByteArray()));
        }
        default -> throw unbuilt();
      }
      advance();
    }
  }

  /** The token's bytes {@code [start, end)} as text; the lexer took them all from ASCII ranges. */
  private String ascii(int start, int end) {
    return new String(b, start, end - start, US_ASCII);
  }

  private static Source source(Token t) {
    return new Source(t.start(), t.end());
  }

  /** The bytes from the start of {@code first} to the end of {@code last}. */
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
    return Stub.unbuilt("the filter's " + token.kind() + " token at byte " + token.start());
  }
}
