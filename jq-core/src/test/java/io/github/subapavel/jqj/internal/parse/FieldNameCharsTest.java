package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chars of a FIELD name (#34 design section 4: {@code .} then {@code [A-Za-z_][A-Za-z0-9_]*}) at the edges of each
 * range, and the char just outside each edge.
 */
class FieldNameCharsTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @Test
  void aNameTakesTheEdgesOfEachRange() {
    // jq-ref 1.8.2, observed 2026-11-11: .zZ09, ._, .Z, .z0 on {"zZ09":1,"_":2,"Z":3,"z0":4} -> 1, 2, 3, 4 (rc 0).
    for (String name : List.of("zZ09", "_", "Z", "z0", "a", "A")) {
      Intrinsic field = (Intrinsic) Parser.parse("." + name);
      assertThat(field.op().name()).as("the operation of .%s", name).isEqualTo("." + name);
      assertThat(field.source()).as("the chars of .%s", name).isEqualTo(new Source(0, name.length() + 1));
    }
  }

  @Test
  void theCharJustOutsideARangeEndsTheName() {
    // jq-ref 1.8.2, observed 2026-11-11 on {"a":1}: each is a syntax error (rc 3), which #34 does not build. After .a
    // each char would extend the name if a range took it, and .a<char> would then parse; after a bare dot it would
    // start one.
    for (String c : List.of("@", "[", "`", "{", "/", ":")) {
      for (String text : List.of(".a" + c, "." + c)) {
        assertThatExceptionOfType(JqCompileException.class)
            .as("parse of %s", text)
            .isThrownBy(() -> Parser.parse(text))
            .withMessageStartingWith(MARK);
      }
    }
  }
}
