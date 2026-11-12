package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The command line's arguments (#38, spec stories 46 to 50): options before or after the filter and after the file,
 * {@code --}, combined short flags, a missing file, an unknown option, a jq option not built yet, and malformed input.
 * Each expected outcome is jq-ref 1.8.2's: the {@code cli/*} cases recorded in {@code oracle/src/test/resources/diff/
 * cli.rec}, or observed on 2026-11-11 where a comment says so. The oracle runs the recorded cases too; this module's
 * coverage counts only its own tests.
 */
class CommandLineTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /** The two lines jq prints after "jq: Unknown option X" (cli/unknown-long). */
  private static final String USAGE =
      "Use jq --help for help with command-line options,\nor see the jq manpage, or online docs at https://jqlang.org\n";

  @TempDir Path cwd;

  /** The outcome of one run: exit code, stdout and stderr. */
  private record Outcome(int rc, String out, String err) {}

  private Outcome run(List<String> args, String stdin) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc = Main.run(new Invocation(args, Map.of(), cwd, new ByteArrayInputStream(stdin.getBytes(UTF_8)), out, err));
    return new Outcome(rc, out.toString(UTF_8), err.toString(UTF_8));
  }

  @BeforeEach
  void files() throws IOException {
    Files.write(cwd.resolve("in.json"), "{\"a\":1}\n".getBytes(UTF_8));
    Files.write(cwd.resolve("arr.json"), "[1]\n".getBytes(UTF_8));
  }

  /**
   * Command lines that print 1: {@code .a} on {@code {"a":1}} from in.json, or from stdin where no file is given.
   * When a file is given, stdin holds {@code {"a":2}}, so a 2 would show that stdin was read instead.
   */
  static Stream<Arguments> printOne() {
    return Stream.of(
        // cli/compact-before-filter, cli/compact-after-filter, cli/compact-after-file, cli/compact-long
        argumentSet("-c .a in.json", List.of("-c", ".a", "in.json")),
        argumentSet(".a -c in.json", List.of(".a", "-c", "in.json")),
        argumentSet(".a in.json -c", List.of(".a", "in.json", "-c")),
        argumentSet("--compact-output .a in.json", List.of("--compact-output", ".a", "in.json")),
        // cli/double-dash-before-filter, cli/double-dash-before-file, cli/double-dash-stdin
        argumentSet("-c -- .a in.json", List.of("-c", "--", ".a", "in.json")),
        argumentSet("-c .a -- in.json", List.of("-c", ".a", "--", "in.json")),
        argumentSet("-c -- .a, stdin", List.of("-c", "--", ".a")),
        // cli/combined-cM, cli/combined-Mc, cli/monochrome-long
        argumentSet("-cM .a in.json", List.of("-cM", ".a", "in.json")),
        argumentSet("-Mc .a in.json", List.of("-Mc", ".a", "in.json")),
        argumentSet("-c --monochrome-output .a in.json", List.of("-c", "--monochrome-output", ".a", "in.json")),
        // observed 2026-11-11: jq-ref -cc .a in.json -> 1 (rc 0)
        argumentSet("-cc .a in.json", List.of("-cc", ".a", "in.json")));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("printOne")
  void builtOptionsGoAnywhere(List<String> args) {
    String stdin = args.contains("in.json") ? "{\"a\":2}\n" : "{\"a\":1}\n";
    assertThat(run(args, stdin)).as("%s", args).isEqualTo(new Outcome(0, "1\n", ""));
  }

  @Test
  void compactWithoutAFilterIsDot() {
    // dot/no-filter; observed 2026-11-11: printf '{"a":1}\n' | jq-ref -c -- -> {"a":1} (rc 0).
    for (List<String> args : List.of(List.of("-c"), List.of("-c", "--"))) {
      assertThat(run(args, "{\"a\":1}\n")).as("%s", args).isEqualTo(new Outcome(0, "{\"a\":1}\n", ""));
    }
  }

  /** Each command line and the option jq names in "Unknown option". */
  static Stream<Arguments> unknownOptions() {
    return Stream.of(
        // cli/unknown-long, cli/unknown-after-file, cli/unknown-in-cluster, cli/unknown-short
        argumentSet("-c --nosuch .a in.json", List.of("-c", "--nosuch", ".a", "in.json"), "--nosuch"),
        argumentSet("-c .a in.json --nosuch", List.of("-c", ".a", "in.json", "--nosuch"), "--nosuch"),
        argumentSet("-cx .a in.json", List.of("-cx", ".a", "in.json"), "-x"),
        argumentSet("-x -c .a in.json", List.of("-x", "-c", ".a", "in.json"), "-x"),
        // observed 2026-11-11: jq-ref names the first byte it does not know, a letter or not.
        argumentSet("-c1 .a in.json", List.of("-c1", ".a", "in.json"), "-1"),
        argumentSet("-c- .a in.json", List.of("-c-", ".a", "in.json"), "--"),
        argumentSet("--- .a in.json", List.of("---", ".a", "in.json"), "---"),
        argumentSet("--compact .a in.json", List.of("--compact", ".a", "in.json"), "--compact"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("unknownOptions")
  void anUnknownOptionPrintsJqsUsageAndExitsTwo(List<String> args, String option) {
    assertThat(run(args, "{\"a\":1}\n")).as("%s", args).isEqualTo(new Outcome(2, "", "jq: Unknown option " + option + "\n" + USAGE));
  }

  /** Each command line and the name jq says it could not open. */
  static Stream<Arguments> missingFiles() {
    return Stream.of(
        // cli/missing-file, cli/missing-file-dot-slash, cli/double-dash-option-is-file
        argumentSet("-c .a missing.json", List.of("-c", ".a", "missing.json"), "missing.json"),
        argumentSet("-c .a ./missing.json", List.of("-c", ".a", "./missing.json"), "./missing.json"),
        argumentSet("-c .a -- -c", List.of("-c", ".a", "--", "-c"), "-c"),
        // observed 2026-11-11: jq-ref -c .a -- --nosuch -> rc 2, Could not open file --nosuch: No such file or directory
        argumentSet("-c .a -- --nosuch", List.of("-c", ".a", "--", "--nosuch"), "--nosuch"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("missingFiles")
  void aMissingFileGivesJqsTextAndExitsTwo(List<String> args, String name) {
    assertThat(run(args, "{\"a\":1}\n"))
        .as("%s", args)
        .isEqualTo(new Outcome(2, "", "jq: error: Could not open file " + name + ": No such file or directory\n"));
  }

  /** jq options jqj does not build yet; jq-ref 1.8.2 knows each (observed 2026-11-11: none prints "Unknown option"). */
  static Stream<Arguments> notBuiltYet() {
    return Stream.of(
        // cli/arg-option: jq-ref prints 1, rc 0.
        argumentSet("-c --arg x 1 .a in.json", List.of("-c", "--arg", "x", "1", ".a", "in.json")),
        argumentSet("--tab .a in.json", List.of("--tab", ".a", "in.json")),
        argumentSet("-c -r .a in.json", List.of("-c", "-r", ".a", "in.json")),
        argumentSet("-cS .a in.json", List.of("-cS", ".a", "in.json")),
        argumentSet("-c .a in.json --debug-trace=all", List.of("-c", ".a", "in.json", "--debug-trace=all")));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("notBuiltYet")
  void aJqOptionNotBuiltYetIsAStubWithExitTwo(List<String> args) {
    Outcome o = run(args, "{\"a\":1}\n");
    assertThat(o.rc()).as("exit code of %s", args).isEqualTo(2);
    assertThat(o.out()).as("stdout of %s", args).isEmpty();
    assertThat(o.err()).as("stderr of %s", args).startsWith(MARK).endsWith("\n");
  }

  @Test
  void aNonAsciiCharacterInAClusterIsAStub() {
    // observed 2026-11-11: jq-ref -cé .a in.json -> rc 2, "jq: Unknown option -" and the first byte of é alone, which
    // is no UTF-8 text: not built.
    Outcome o = run(List.of("-cé", ".a", "in.json"), "{\"a\":1}\n");
    assertThat(o.rc()).as("exit code").isEqualTo(2);
    assertThat(o.out()).as("stdout").isEmpty();
    assertThat(o.err()).as("stderr").startsWith(MARK).endsWith("\n");
  }

  @Test
  void aDashWithoutALetterIsTheFilter() {
    // observed 2026-11-11: jq-ref -_ .a in.json -> rc 3, "_/0 is not defined"; jq-ref - . -> rc 3, a syntax error;
    // printf '{"a":1}\n' | jq-ref -c -1 -> -1. Each is the filter, which jqj does not compile yet: exit code 3, not 2.
    for (List<String> args : List.of(List.of("-_", ".a", "in.json"), List.of("-", "."), List.of("-c", "-1"))) {
      Outcome o = run(args, "{\"a\":1}\n");
      assertThat(o.rc()).as("exit code of %s", args).isEqualTo(3);
      assertThat(o.err()).as("stderr of %s", args).startsWith(MARK);
    }
  }

  @Test
  void aDirectoryAsTheFileIsAStub() throws IOException {
    // observed 2026-11-11: mkdir dir; jq-ref -c .a dir -> rc 2, "jq: error: Is a directory": not built.
    Files.createDirectory(cwd.resolve("dir"));
    Outcome o = run(List.of("-c", ".a", "dir"), "{\"a\":1}\n");
    assertThat(o.rc()).as("exit code").isEqualTo(2);
    assertThat(o.out()).as("stdout").isEmpty();
    assertThat(o.err()).as("stderr").startsWith(MARK).endsWith("\n");
  }

  @Test
  void theFileArgumentIsNamedAsGivenInAnError() {
    // cli/error-file-name, cli/error-file-name-dot-slash
    assertThat(run(List.of("-c", ".a", "arr.json"), "{\"a\":1}\n"))
        .isEqualTo(new Outcome(5, "", "jq: error (at arr.json:1): Cannot index array with string (\"a\")\n"));
    assertThat(run(List.of("-c", ".a", "./arr.json"), "{\"a\":1}\n"))
        .isEqualTo(new Outcome(5, "", "jq: error (at ./arr.json:1): Cannot index array with string (\"a\")\n"));
  }

  /**
   * Grilling round 1 item 7: malformed input exits 5 with jq-ref's stdout, and its text, a Stub until spec 5, is not
   * pinned; only jq's frame is, with jq-ref's line and column.
   */
  @Test
  void malformedInputExitsFiveWithJqRefsStdout() throws IOException {
    // cli/malformed-stdin: printf '[1,2' | jq-ref -c .a -> rc 5, stdout empty,
    //   jq: parse error: Unfinished JSON term at EOF at line 1, column 4
    // cli/malformed-file: printf '{"a":\n' > bad.json; jq-ref -c .a bad.json -> rc 5, stdout empty,
    //   jq: parse error: Unfinished JSON term at EOF at line 2, column 0
    Files.write(cwd.resolve("bad.json"), "{\"a\":\n".getBytes(UTF_8));
    Outcome stdin = run(List.of("-c", ".a"), "[1,2");
    Outcome file = run(List.of("-c", ".a", "bad.json"), "{\"a\":1}\n");

    assertThat(stdin.rc()).as("exit code, stdin").isEqualTo(5);
    assertThat(stdin.out()).as("stdout, stdin: jq-ref's").isEqualTo("");
    assertThat(stdin.err()).as("stderr, stdin: jq's frame").startsWith("jq: parse error: ").endsWith(" at line 1, column 4\n");
    assertThat(file.rc()).as("exit code, file").isEqualTo(5);
    assertThat(file.out()).as("stdout, file: jq-ref's").isEqualTo("");
    assertThat(file.err()).as("stderr, file: jq's frame").startsWith("jq: parse error: ").endsWith(" at line 2, column 0\n");
  }
}
