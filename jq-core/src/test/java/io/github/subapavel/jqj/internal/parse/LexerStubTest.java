package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Pipe;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ..} is jq's recurse, which #34 does not build (#34 design section 4): a dot followed by a dot is a Stub, also
 * when a name follows, so {@code ..a} never reads as {@code . .a}.
 */
class LexerStubTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @Test
  void aDotAfterADotIsAStub() {
    for (String text : List.of("..a", "(..a)", ". | ..a", ".. .a")) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("parse of %s", text)
          .isThrownBy(() -> Parser.parse(text))
          .withMessageStartingWith(MARK);
    }
    // Witness: a dot, a space, then the name is the identity piped into .a (field/dot-space-field).
    assertThat(Parser.parse(". .a")).as("parse of . .a").isInstanceOf(Pipe.class);
  }
}
