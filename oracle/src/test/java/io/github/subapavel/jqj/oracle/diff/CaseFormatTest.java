package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The case and recording format of the differential oracle (ADR-0020). */
class CaseFormatTest {

  private static List<DiffCase> cases(String text) {
    return CaseFormat.parse("t.cases", "t", text.getBytes(UTF_8)).stream().map(b -> DiffCase.of("t.cases", b)).toList();
  }

  @Test
  void aCaseIsItsArgumentsInOrderItsStdinFilesAndEnvironment() {
    DiffCase c =
        cases("# comment\n\ncase t/x\narg -c\nfilter .a\narg in.json\nstdin [1]\\n\nfile in.json {}\nfile empty.json\nenv K=a=b\nend\n").get(0);

    assertThat(c.id()).isEqualTo("t/x");
    assertThat(c.args()).containsExactly("-c", ".a", "in.json");
    assertThat(c.stdin()).isEqualTo("[1]\n".getBytes(UTF_8));
    assertThat(c.files()).containsOnlyKeys("in.json", "empty.json");
    assertThat(c.files().get("in.json")).isEqualTo("{}".getBytes(UTF_8));
    assertThat(c.files().get("empty.json")).isEmpty();
    assertThat(c.env()).isEqualTo(Map.of("K", "a=b"));
  }

  @Test
  void aCaseWithoutStdinHasEmptyInput() {
    assertThat(cases("case t/x\nfilter .\nend\n").get(0).stdin()).isEmpty();
  }

  @Test
  void aCaseNamesTheDeviationItPins() {
    assertThat(cases("case t/x\ndeviation nul\nend\n").get(0).deviation()).isEqualTo("nul");
    assertThat(cases("case t/x\nend\n").get(0).deviation()).isNull();
  }

  @Test
  void theDefIsTwelveHexOfTheSha256OfTheCaseBlock() {
    // printf 'case t/x\nfilter .\nend\n' | sha256sum
    assertThat(cases("# header\ncase t/x\nfilter .\nend\n").get(0).def()).isEqualTo("98ada9f8a537");
    assertThat(cases("case t/x\nfilter .b\nend\n").get(0).def()).isNotEqualTo("98ada9f8a537");
    assertThat(cases("case t/w\nfilter .w\nend\n\ncase t/x\nfilter .\nend\n").get(1).def()).isEqualTo("98ada9f8a537");
  }

  @Test
  void escapesStandForTheirBytesAndBytesEscapeBackTheSameWay() {
    // a \ LF TAB NUL DEL é, a malformed byte before a plain b, a space, CR
    byte[] bytes = HexFormat.of().parseHex("615c0a09007fc3a9ff62200d");
    String escaped = "a\\\\\\n\\t\\x00\\x7fé\\xffb \\x0d";

    assertThat(CaseFormat.escape(bytes)).isEqualTo(escaped);
    assertThat(CaseFormat.unescape(escaped)).isEqualTo(bytes);
    assertThat(CaseFormat.unescape("\\xFF")).isEqualTo(new byte[] {(byte) 0xff});
  }

  static Stream<Arguments> malformed() {
    return Stream.of(
        Arguments.argumentSet("not UTF-8", new byte[] {'c', (byte) 0xff, '\n'}, "not UTF-8"),
        Arguments.argumentSet("a line outside a block", bytes("filter .\n"), "t.cases:1: expected 'case t/<name>'"),
        Arguments.argumentSet("an id of another family", bytes("case u/x\nend\n"), "not t/<name>"),
        Arguments.argumentSet("an id without a name", bytes("case t/\nend\n"), "not t/<name>"),
        Arguments.argumentSet("an id twice", bytes("case t/x\nend\ncase t/x\nend\n"), "t.cases:3: case t/x occurs twice"),
        Arguments.argumentSet("a block without end", bytes("case t/x\nfilter .\n"), "t.cases:1: case t/x has no 'end'"),
        Arguments.argumentSet("an expected output written by hand", bytes("case t/x\nfilter .\nstdout {}\nend\n"), "t.cases:3: case t/x: unknown key 'stdout'"),
        Arguments.argumentSet("a line starting with a space", bytes("case t/x\n filter .\nend\n"), "unknown key ''"),
        Arguments.argumentSet("a file without a name", bytes("case t/x\nfile  {}\nend\n"), "'file' needs a name"),
        Arguments.argumentSet("stdin twice", bytes("case t/x\nstdin 1\nstdin 2\nend\n"), "'stdin' given twice"),
        Arguments.argumentSet("deviation twice", bytes("case t/x\ndeviation a\ndeviation b\nend\n"), "'deviation' given twice"),
        Arguments.argumentSet("deviation without a slug", bytes("case t/x\ndeviation\nend\n"), "'deviation' needs a slug"),
        Arguments.argumentSet("env without =", bytes("case t/x\nenv K\nend\n"), "'env' needs K=V"),
        Arguments.argumentSet("env with an empty name", bytes("case t/x\nenv =v\nend\n"), "'env' needs K=V"),
        Arguments.argumentSet("a lone backslash", bytes("case t/x\nstdin a\\\nend\n"), "lone"),
        Arguments.argumentSet("\\x without two hex digits", bytes("case t/x\nstdin \\x4\nend\n"), "two hex digits"),
        Arguments.argumentSet("\\x with a non-hex digit", bytes("case t/x\nstdin \\x4g\nend\n"), "two hex digits"),
        Arguments.argumentSet("\\x with a non-hex first digit", bytes("case t/x\nstdin \\xg4\nend\n"), "two hex digits"),
        Arguments.argumentSet("an unknown escape", bytes("case t/x\nstdin \\r\nend\n"), "unknown escape '\\r'"));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(UTF_8);
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("malformed")
  void aMalformedCaseFileIsRefusedWithItsLine(byte[] content, String message) {
    assertThatThrownBy(() -> CaseFormat.parse("t.cases", "t", content).forEach(b -> DiffCase.of("t.cases", b)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  static Stream<Arguments> malformedRecordings() {
    return Stream.of(
        Arguments.argumentSet("an unknown key", "case t/x\ndef 0\nrc 0\nstdout\nstderr\nfilter .\nend\n", "unknown key 'filter'"),
        Arguments.argumentSet("a key twice", "case t/x\ndef 0\nrc 0\nrc 1\nstdout\nstderr\nend\n", "'rc' given twice"),
        Arguments.argumentSet("a missing key", "case t/x\ndef 0\nrc 0\nstdout\nend\n", "needs def, rc, stdout and stderr"),
        Arguments.argumentSet("an rc that is not an integer", "case t/x\ndef 0\nrc five\nstdout\nstderr\nend\n", "rc 'five'"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("malformedRecordings")
  void aMalformedRecordingIsRefused(String content, String message) {
    assertThatThrownBy(() -> CaseFormat.parse("t.rec", "t", bytes(content)).forEach(b -> Recording.of("t.rec", b)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  @Test
  void aRecordingReadsBackWhatTheRecorderWrites(@TempDir Path dir) throws Exception {
    Recording r = new Recording("t/x", "98ada9f8a537", new Outcome(5, new byte[0], "e\t\\\n".getBytes(UTF_8)));
    Path file = dir.resolve("t.rec");
    Files.write(file, Recording.file("# jq-1.8.2 sha256 00", List.of(r)));

    assertThat(Files.readString(file, UTF_8))
        .isEqualTo("# jq-1.8.2 sha256 00\n\ncase t/x\ndef 98ada9f8a537\nrc 5\nstdout\nstderr e\\t\\\\\\n\nend\n");
    Recording back = Recording.read(file).get("t/x");
    assertThat(back.def()).isEqualTo("98ada9f8a537");
    assertThat(back.outcome().sameAs(r.outcome())).isTrue();
    assertThat(Recording.read(dir.resolve("absent.rec"))).isEmpty();
  }

  /** ADR-0025: a family is the path relative to {@code diff/}, so {@code diff/gen/values-v1.cases} holds gen ids. */
  @Test
  void aFamilyIsThePathBelowTheNearestDiffDirectory(@TempDir Path dir) throws Exception {
    assertThat(CaseFormat.family(Path.of("oracle/src/test/resources/diff/gen/values-v1.rec"))).isEqualTo("gen/values-v1");
    assertThat(CaseFormat.family(Path.of("/r/diff/a/diff/basics.cases"))).isEqualTo("basics");
    assertThat(CaseFormat.family(Path.of("/r/json/json.cases"))).isEqualTo("json");
    assertThat(CaseFormat.family(Path.of("t.cases"))).isEqualTo("t");
    assertThat(CaseFormat.family(dir, dir.resolve("gen/sub/x.cases"))).isEqualTo("gen/sub/x");

    Path file = dir.resolve("diff/gen/v.rec");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "case gen/v/1\ndef 0\nrc 0\nstdout\nstderr\nend\n");
    assertThat(Recording.read(file)).containsOnlyKeys("gen/v/1");
    assertThat(CaseFormat.read(file, "gen/v")).hasSize(1);
    assertThatThrownBy(() -> CaseFormat.read(file, "v")).hasMessageContaining("is not v/<name>");
  }

  @Test
  void outcomesDifferByExitCodeOrByAnyByteOfEitherStream() {
    Outcome o = new Outcome(0, bytes("a"), bytes("b"));

    assertThat(o.sameAs(new Outcome(0, bytes("a"), bytes("b")))).isTrue();
    assertThat(o.sameAs(new Outcome(1, bytes("a"), bytes("b")))).isFalse();
    assertThat(o.sameAs(new Outcome(0, bytes("x"), bytes("b")))).isFalse();
    assertThat(o.sameAs(new Outcome(0, bytes("a"), bytes("x")))).isFalse();
  }

  @Test
  void aCaseDirectoryIsDeletedWhenAFileCannotBeWritten(@TempDir Path dir) throws Exception {
    assertThatThrownBy(() -> CaseDirectory.of(Map.of("missing/sub.json", new byte[0]), dir)).isInstanceOf(java.io.UncheckedIOException.class);

    try (Stream<Path> left = Files.list(dir)) {
      assertThat(left).isEmpty();
    }
  }
}
