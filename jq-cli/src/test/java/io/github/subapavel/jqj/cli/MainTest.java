package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** The stub shell of the in-process entry (ADR-0020, ADR-0024): until the command line is built, every run is a Stub. */
class MainTest {

  static Stream<Arguments> commandLines() {
    return Stream.of(
        Arguments.argumentSet("no argument", List.of()),
        Arguments.argumentSet("-c .", List.of("-c", ".")),
        Arguments.argumentSet(".a", List.of(".a")),
        Arguments.argumentSet("unknown option", List.of("--badopt")));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("commandLines")
  void everyCommandLineEndsWithAStubAndANonZeroExit(List<String> args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation =
        new Invocation(args, Map.of(), Path.of("."), new ByteArrayInputStream("{\"a\":1}\n".getBytes(UTF_8)), out, err);

    int rc = Main.run(invocation);

    assertThat(rc).isNotZero();
    assertThat(out.toByteArray()).isEmpty();
    assertThat(err.toString(UTF_8)).startsWith("jqj stub: ");
  }

  @Test
  void aFailingStderrIsReportedNotSwallowed() {
    OutputStream broken =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("stderr closed");
          }
        };
    Invocation invocation =
        new Invocation(List.of("."), Map.of(), Path.of("."), new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), broken);

    assertThatThrownBy(() -> Main.run(invocation)).isInstanceOf(UncheckedIOException.class).hasMessageContaining("stderr closed");
  }
}
