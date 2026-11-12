package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-process entry (ADR-0020, ADR-0024). #33 built {@code -c .}, so its entry ended with its Stub (#33 design C12)
 * and {@link #compactDotPrintsTheDocumentAndExitsZero} checks it. #34 built {@code .a} and jq's error line, which
 * {@link #filterErrorPrintsJqsLineAndExitsFive} and {@link #fileArgumentNamesTheFileInTheErrorLine} check. Every
 * other command line is still a Stub: no argument is {@code .} pretty-printed and {@code .a} without {@code -c} is
 * pretty output (#37). #38 built jq's text for an unknown option, which {@link #unknownOptionPrintsJqsUsageAndExitsTwo}
 * checks.
 */
class MainTest {

  static Stream<Arguments> commandLines() {
    return Stream.of(
        Arguments.argumentSet("no argument", List.of()),
        Arguments.argumentSet(".a", List.of(".a")));
  }

  @Test
  void unknownOptionPrintsJqsUsageAndExitsTwo() {
    // jq-ref 1.8.2, recorded in diff/cli.rec (cli/unknown-long, for --nosuch) and observed 2026-11-12:
    //   printf '{"a":1}\n' | jq-ref --badopt -> rc 2, stdout empty, stderr these three lines.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation =
        new Invocation(List.of("--badopt"), Map.of(), Path.of("."), new ByteArrayInputStream("{\"a\":1}\n".getBytes(UTF_8)), out, err);

    int rc = Main.run(invocation);

    assertThat(rc).as("exit code").isEqualTo(2);
    assertThat(out.toByteArray()).as("stdout").isEmpty();
    assertThat(err.toString(UTF_8))
        .as("stderr")
        .isEqualTo(
            "jq: Unknown option --badopt\nUse jq --help for help with command-line options,\n"
                + "or see the jq manpage, or online docs at https://jqlang.org\n");
  }

  @Test
  void compactDotPrintsTheDocumentAndExitsZero() {
    // jq-ref 1.8.2, recorded in diff/basics.rec (basics/compact-object): printf '{"a":1}\n' | jq-ref -c .
    //   -> rc 0, stdout {"a":1} and LF, stderr empty.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Invocation invocation =
        new Invocation(List.of("-c", "."), Map.of(), Path.of("."), new ByteArrayInputStream("{\"a\":1}\n".getBytes(UTF_8)), out, err);

    int rc = Main.run(invocation);

    assertThat(rc).as("exit code").isZero();
    assertThat(out.toString(UTF_8)).as("stdout").isEqualTo("{\"a\":1}\n");
    assertThat(err.toString(UTF_8)).as("stderr").isEmpty();
  }

  @Test
  void filterErrorPrintsJqsLineAndExitsFive() {
    // jq-ref 1.8.2, recorded in diff/field.rec (field/error-array, field/line-inner-lfs) and observed 2026-11-11:
    //   printf '[1]\n' | jq-ref -c .a           -> rc 5, stdout empty, jq: error (at <stdin>:1): Cannot index array with string ("a")
    //   printf '[\n1\n]' | jq-ref -c .a         -> rc 5, jq: error (at <stdin>:2): Cannot index array with string ("a")
    //   printf '{"a":[1]}\n' | jq-ref -c '.a | .b' -> rc 5, jq: error (at <stdin>:1): Cannot index array with string ("b")
    String[][] cases = {
      {".a", "[1]\n", "jq: error (at <stdin>:1): Cannot index array with string (\"a\")\n"},
      {".a", "[\n1\n]", "jq: error (at <stdin>:2): Cannot index array with string (\"a\")\n"},
      {".a | .b", "{\"a\":[1]}\n", "jq: error (at <stdin>:1): Cannot index array with string (\"b\")\n"},
    };
    for (String[] c : cases) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Invocation invocation = new Invocation(List.of("-c", c[0]), Map.of(), Path.of("."), new ByteArrayInputStream(c[1].getBytes(UTF_8)), out, err);

      int rc = Main.run(invocation);

      assertThat(err.toString(UTF_8)).as("stderr of -c %s", c[0]).isEqualTo(c[2]);
      assertThat(rc).as("exit code of -c %s", c[0]).isEqualTo(5);
      assertThat(out.toByteArray()).as("stdout of -c %s", c[0]).isEmpty();
    }
  }

  @Test
  void fileArgumentNamesTheFileInTheErrorLine(@TempDir Path cwd) throws IOException {
    // jq-ref 1.8.2, recorded (field/error-file-argument) and observed 2026-11-11: the file argument as given.
    //   printf '[1]\n' > in.json; jq-ref -c .a in.json            -> jq: error (at in.json:1): Cannot index array with string ("a")
    //   jq-ref -c .a ./in.json                                    -> jq: error (at ./in.json:1): ...
    //   printf '\n\n5\n' > sub/doc.json; jq-ref -c .b sub/doc.json -> jq: error (at sub/doc.json:3): Cannot index number with string ("b")
    Files.write(cwd.resolve("in.json"), "[1]\n".getBytes(UTF_8));
    Files.createDirectories(cwd.resolve("sub"));
    Files.write(cwd.resolve("sub/doc.json"), "\n\n5\n".getBytes(UTF_8));
    String[][] cases = {
      {".a", "in.json", "jq: error (at in.json:1): Cannot index array with string (\"a\")\n"},
      {".a", "./in.json", "jq: error (at ./in.json:1): Cannot index array with string (\"a\")\n"},
      {".b", "sub/doc.json", "jq: error (at sub/doc.json:3): Cannot index number with string (\"b\")\n"},
    };
    for (String[] c : cases) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      // stdin holds another document: the error must name the file and count its lines.
      Invocation invocation = new Invocation(List.of("-c", c[0], c[1]), Map.of(), cwd, new ByteArrayInputStream("[9]\n".getBytes(UTF_8)), out, err);

      int rc = Main.run(invocation);

      assertThat(err.toString(UTF_8)).as("stderr of -c %s %s", c[0], c[1]).isEqualTo(c[2]);
      assertThat(rc).as("exit code of -c %s %s", c[0], c[1]).isEqualTo(5);
      assertThat(out.toByteArray()).as("stdout of -c %s %s", c[0], c[1]).isEmpty();
    }
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
