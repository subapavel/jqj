package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Node;

/**
 * The parse and the resolution as two steps (ADR-0032): {@code Parser.parse} builds the AST and throws every lexical,
 * syntax and recovery error; {@code Resolver.resolve} lowers the AST to IR, a Stub for a kind it does not lower yet.
 */
final class ParseAssertions {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  static final String MARK = "jqj stub: ";

  /** jq's whole text for a program with no top-level filter (ADR-0032), which is no syntax error. */
  static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")\njq: 1 compile error";

  private ParseAssertions() {}

  /** The IR of {@code text}: its AST, resolved. */
  static Node ir(String text) {
    return Resolver.resolve(Parser.parse(text));
  }

  /** {@code text} parses, and its resolution is a Stub: a construct the parser accepts and the resolver does not lower. */
  static void parsesToAStub(String text) {
    assertThatCode(() -> Parser.parse(text)).as("parse of %s", show(text)).doesNotThrowAnyException();
    assertThatExceptionOfType(JqCompileException.class)
        .as("resolve of %s", show(text))
        .isThrownBy(() -> ir(text))
        .withMessageStartingWith(MARK);
  }

  /**
   * The parse of {@code text} throws its syntax error: {@code blocks} error blocks, the first one jq's {@code syntax
   * error, unexpected <unexpected>} part at jq's line and column, which the exception carries too.
   */
  static void syntaxError(String text, String unexpected, int line, int column, int blocks) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("parse of %s", show(text)).isThrownBy(() -> Parser.parse(text)).actual();
    assertThat(e.blocks()).as("blocks of %s", show(text)).hasSize(blocks);
    assertThat(e.blocks().getFirst())
        .as("first block of %s", show(text))
        .startsWith("jq: error: syntax error, unexpected " + unexpected)
        .contains(" at <top-level>, line " + line + ", column " + column + ":\n");
    assertThat(e.line()).as("line of %s", show(text)).isEqualTo(line);
    assertThat(e.column()).as("column of %s", show(text)).isEqualTo(column);
  }

  /** {@code text} has no top-level filter: jq's not-given text, from the parse or the resolution (ADR-0032). */
  static void notGiven(String text) {
    assertThatExceptionOfType(JqCompileException.class).as("compile of %s", show(text)).isThrownBy(() -> ir(text)).withMessage(NOT_GIVEN);
  }

  /** The text with its control characters spelled out, for a readable description. */
  static String show(String text) {
    return "'" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
  }
}
