package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code JqEngine.compile} after #33 (design section 1): {@code .}, with SP, TAB, CR or LF around it, compiles to the
 * identity and runs in push form (ADR-0008); any other text is a Stub, a JqCompileException with the mark (ADR-0024).
 * Exported types only.
 */
class CompileTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /** jq-ref 1.8.2, observed 2026-11-11: printf '[1]' | jq-ref -c "$(printf ' \t\r\n.\n\t ')" -> [1] (rc 0). */
  private static final String DOT_IN_WHITESPACE = " \t\r\n.\n\t ";

  @Test
  void stubConstructThrowsCompileException() {
    JqEngine engine = JqEngine.builder().build();
    // jq-ref 1.8.2, observed 2026-11-11, each with printf '[1]' | jq-ref -c <text>:
    //   1 -> 1 (rc 0); .a -> "Cannot index array with string" (rc 5); .. -> [1] 1 (rc 0);
    //   '' and '   ' -> "Top-level program not given (try \".\")" (rc 3);
    //   FF or VT before the dot -> "syntax error, unexpected INVALID_CHARACTER" (rc 3): the filter's whitespace is
    //   SP, TAB, CR and LF only. #33 builds none of them, so each ends in the Stub.
    for (String text : List.of("1", ".a", "..", "", "   ", "\f.", "\u000b.")) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("compile of %s", quoted(text))
          .isThrownBy(() -> engine.compile(text))
          .withMessageStartingWith(MARK);
    }

    // Witness: the built construct compiles and runs, so the Stubs above are not a Stub for every text.
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "[1]").root();
    for (String text : List.of(".", DOT_IN_WHITESPACE)) {
      List<JqValue> results = new ArrayList<>();
      boolean ranOut =
          engine
              .compile(text)
              .run(
                  root,
                  Bindings.builder().build(),
                  v -> {
                    results.add(v);
                    return true;
                  },
                  workspace);
      assertThat(results).as("results of %s on [1]", quoted(text)).containsExactly(root);
      assertThat(ranOut).as("run's answer for %s when the sink never stops", quoted(text)).isTrue();
    }
  }

  @Test
  void dotEmitsTheRootOnceAndPassesTheSinksStopOn() {
    // ADR-0016: run is "true when the results ran out, false when the sink stopped" (ADR-0008's stop).
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "{\"a\":[1,2]}").root();
    List<JqValue> results = new ArrayList<>();

    boolean ranOut =
        JqEngine.builder()
            .build()
            .compile(".")
            .run(
                root,
                Bindings.builder().build(),
                v -> {
                  results.add(v);
                  return false;
                },
                workspace);

    assertThat(ranOut).as("run's answer after the sink stopped it").isFalse();
    assertThat(results).as("results handed over before the stop").containsExactly(root);
  }

  /** The text with its control characters spelled out, for a readable description. */
  private static String quoted(String text) {
    return "'" + text.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n").replace("\f", "\\f").replace("\u000b", "\\v") + "'";
  }
}
