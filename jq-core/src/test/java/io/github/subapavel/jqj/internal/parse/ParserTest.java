package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Source;
import org.junit.jupiter.api.Test;

/** The IR the parser makes of {@code .} (#33 design section 1): an Identity whose Source is the dot's chars. */
class ParserTest {

  @Test
  void theIdentitysSourceIsTheDotsChars() {
    // The whitespace before the dot is skipped, so the dot is char 2 of " \t.\n": its source is [2, 3).
    assertThat(Parser.parse(" \t.\n")).as("parse of ' \\t.\\n'").isEqualTo(new Identity(new Source(2, 3)));
    assertThat(Parser.parse(".")).as("parse of '.'").isEqualTo(new Identity(new Source(0, 1)));
  }
}
