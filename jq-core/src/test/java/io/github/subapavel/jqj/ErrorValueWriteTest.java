package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Writing an error value (#34 design section 7): the string a filter error carries is built at run time, and its
 * writer arm is #35's (jq's re-escape), so writing it ends in the Stub. Exported types only.
 */
class ErrorValueWriteTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @Test
  void writingABuiltStringIsAStub() {
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "[1]").root();
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class)
            .as("run of .a on [1]")
            .isThrownBy(() -> JqEngine.builder().build().compile(".a").run(root, Bindings.builder().build(), v -> true, workspace))
            .actual();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JqSink sink = JsonWriter.sink(workspace, out, OutputOptions.compact());
    assertThatExceptionOfType(JqCompileException.class)
        .as("writing the error value of .a on [1]")
        .isThrownBy(() -> sink.emit(e.value()))
        .withMessageStartingWith(MARK);
    assertThat(out.toByteArray()).as("bytes written before the Stub").isEmpty();

    // Witness: the same sink writes a document's value.
    sink.emit(root);
    assertThat(out.toString(UTF_8)).as("bytes written for [1]").isEqualTo("[1]\n");
  }
}
