package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * {@code JqEngine.compile} on a bad escape run (ADR-0031, GLOSSARY "Escape run", "Error block"): every bad run is
 * collected, the exception carries one error block per run and the first block's line and column, in bytes. A Stub met
 * during the parse wins over the collected errors. The command line's text is checked by diff/literal.cases; this test
 * checks what the oracle cannot see: the exception's line, column and block list.
 */
class LexicalErrorTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void everyBadEscapeRunIsOneErrorBlock() {
    // jq-ref 1.8.2, recorded in diff/literal.rec (literal/error-two-blocks): "\va\q" -> rc 3, these two blocks, then
    // "jq: 2 compile errors".
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class)
            .as("compile of \"\\va\\q\"")
            .isThrownBy(() -> ENGINE.compile("\"\\va\\q\""))
            .actual();

    assertThat(e.blocks())
        .as("blocks")
        .containsExactly(
            "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\v\"') at <top-level>, line 1, column 2:\n"
                + "    \"\\va\\q\"\n"
                + "     ^^",
            "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\q\"') at <top-level>, line 1, column 5:\n"
                + "    \"\\va\\q\"\n"
                + "        ^^");
    assertThat(e.getMessage()).as("message").endsWith("\njq: 2 compile errors");
    assertThat(e.line()).as("line").isEqualTo(1);
    assertThat(e.column()).as("column").isEqualTo(2);
  }

  @Test
  void theLineAndColumnCountBytesAndLineFeeds() {
    // jq-ref 1.8.2, recorded: literal/error-byte-column ("é\v": "line 1, column 4", é is two bytes) and
    // literal/error-line-3 (".", "|" and "a\v" on three lines: "line 3, column 3").
    JqCompileException byteColumn =
        assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile("\"é\\v\"")).actual();
    assertThat(byteColumn.column()).as("column of \"é\\v\"").isEqualTo(4);
    assertThat(byteColumn.line()).as("line of \"é\\v\"").isEqualTo(1);

    JqCompileException thirdLine =
        assertThatExceptionOfType(JqCompileException.class).isThrownBy(() -> ENGINE.compile(".\n|\n\"a\\v\"")).actual();
    assertThat(thirdLine.line()).as("line of the escape on line 3").isEqualTo(3);
    assertThat(thirdLine.column()).as("column of the escape on line 3").isEqualTo(3);
    assertThat(thirdLine.blocks())
        .as("blocks of the escape on line 3")
        .containsExactly(
            "jq: error: Invalid escape at line 1, column 4 (while parsing '\"\\v\"') at <top-level>, line 3, column 3:\n"
                + "    \"a\\v\"\n"
                + "      ^^");
  }

  @Test
  void aStubMetDuringTheParseWinsOverTheCollectedErrors() {
    // ADR-0031: lexical errors are thrown together at the end of the parse; a Stub met earlier wins. .[0] stays a Stub
    // until #63 (jq-ref 1.8.2 gives the escape error for both texts, rc 3; jqj's Stub is the construct not built yet).
    for (String text : new String[] {"\"\\v\" | .[0]", ".[0] | \"\\v\""}) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("compile of %s", text)
          .isThrownBy(() -> ENGINE.compile(text))
          .withMessageStartingWith(MARK);
    }
    // Witness: without the Stub the same escape is an error block, not a Stub.
    assertThatExceptionOfType(JqCompileException.class)
        .as("compile of \"\\v\" | .")
        .isThrownBy(() -> ENGINE.compile("\"\\v\" | ."))
        .withMessageStartingWith("jq: error: Invalid escape at line 1, column 4");
  }
}
