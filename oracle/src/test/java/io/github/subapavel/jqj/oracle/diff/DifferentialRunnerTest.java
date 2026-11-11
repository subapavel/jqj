package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.cli.Invocation;
import io.github.subapavel.jqj.cli.Main;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The differential runner over the command line's in-process entry (ADR-0020, ADR-0024), on the cases recorded from
 * jq-ref in {@code diff/basics.rec}. Entries other than the stub shell stand in for a built command line.
 */
class DifferentialRunnerTest {

  @TempDir Path scratch;

  /** Loaded per test, not in a static initializer: PIT never re-runs one under a mutant. */
  private static DiffCorpus.Entry basics(String id) {
    return DiffCorpus.load(Corpus.casesDir()).stream().filter(e -> e.c().id().equals(id)).findFirst().orElseThrow();
  }

  private Verdict run(String id, ToIntFunction<Invocation> entry) {
    DiffCorpus.Entry e = basics(id);
    return new DiffRunner(entry, scratch).run(e.c(), e.recording());
  }

  @Test
  void theCorpusHoldsEveryCaseSortedByIdEachWithItsRecording() {
    List<DiffCorpus.Entry> all = DiffCorpus.load(Corpus.casesDir());

    assertThat(all)
        .extracting(e -> e.c().id())
        .containsExactly("basics/compact-object", "basics/field-of-array", "basics/file-argument", "basics/syntax-error-eof");
    assertThat(all).allSatisfy(e -> assertThat(e.recording().id()).isEqualTo(e.c().id()));
  }

  /** An entry standing in for jqj: writes the given bytes and returns rc. */
  private static ToIntFunction<Invocation> jqj(int rc, String stdout, String stderr) {
    return inv -> {
      try {
        inv.out().write(stdout.getBytes(UTF_8));
        inv.err().write(stderr.getBytes(UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return rc;
    };
  }

  @Test
  void aRecordedCaseFailsAgainstTheStubShellWithAReadableDiff() {
    Verdict v = run("basics/compact-object", Main::run);

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.diff())
        .contains("basics/compact-object")
        .contains("rc      jq-ref 0\n")
        .contains("stdout  jq-ref {\"a\":1}\\n\n")
        .contains("        jqj    (empty)\n");
    assertThat(v.detail()).doesNotContain("\n").doesNotContain("\t");
  }

  @Test
  void aRecordedSyntaxErrorCaseIsFailAgainstTheStubShell() {
    assertThat(run("basics/syntax-error-eof", Main::run).kind()).isEqualTo(Verdict.Kind.FAIL);
  }

  @Test
  void aStubIsFailEvenWhereTheSyntaxErrorRuleComparesOnlyRcAndStdout() {
    Verdict v = run("basics/syntax-error-eof", jqj(3, "", "jqj stub: the parser is not built yet\n"));

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.detail()).contains("Stub");
  }

  @Test
  void aSyntaxErrorCaseComparesRcAndStdoutOnly() {
    Verdict pass = run("basics/syntax-error-eof", jqj(3, "", "jq: error: jqj's own syntax error text\n"));
    assertThat(pass.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(pass.detail()).contains("stderr not compared");
    assertThat(run("basics/syntax-error-eof", jqj(2, "", "")).kind()).isEqualTo(Verdict.Kind.FAIL);
  }

  @Test
  void aCaseWhoseOutputMatchesTheRecordingByteForBytePasses() {
    ToIntFunction<Invocation> cat =
        inv -> {
          try {
            inv.in().transferTo(inv.out());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return 0;
        };
    // -c . on compact input prints the input unchanged, so an entry that copies stdin to stdout agrees with jq-ref.
    Verdict v = run("basics/compact-object", cat);

    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(v.detail()).isEmpty();
  }

  @Test
  void stderrIsComparedByteForByteOutsideSyntaxErrors() {
    String recorded = "jq: error (at <stdin>:0): Cannot index array with string (\"a\")\n";
    assertThat(run("basics/field-of-array", jqj(5, "", recorded)).kind()).isEqualTo(Verdict.Kind.PASS);

    Verdict v = run("basics/field-of-array", jqj(5, "", "jq: error (at <stdin>:1): Cannot index array with string (\"a\")\n"));
    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.diff()).contains("stderr  jq-ref " + CaseFormat.escape(recorded.getBytes(UTF_8))).contains("rc      same");

    Verdict fromTheFirstByte = run("basics/field-of-array", jqj(5, "", "other\n"));
    assertThat(fromTheFirstByte.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(fromTheFirstByte.detail()).isEqualTo("stderr differs at byte 0");
  }

  @Test
  void theEntryGetsTheCaseEnvironmentOnTopOfHomeTzAndLcAll() {
    String text = "case t/env\narg -n\nfilter $ENV.K\nenv K=v\nenv TZ=Asia/Tokyo\nend\n";
    DiffCase c = DiffCase.of("t.cases", CaseFormat.parse("t.cases", "t", text.getBytes(UTF_8)).get(0));
    Map<String, String> seenEnv = new HashMap<>();

    new DiffRunner(
            inv -> {
              seenEnv.putAll(inv.env());
              return 0;
            },
            scratch)
        .run(c, null);

    assertThat(seenEnv).containsEntry("K", "v").containsEntry("TZ", "Asia/Tokyo").containsEntry("LC_ALL", "C").containsKey("HOME");
  }

  @Test
  void theEntryGetsTheCaseFilesInItsWorkingDirectoryAndExactlyTheCaseEnvironment() {
    Map<String, String> seenEnv = new HashMap<>();
    Path[] seenCwd = new Path[1];
    ToIntFunction<Invocation> probe =
        inv -> {
          seenEnv.putAll(inv.env());
          seenCwd[0] = inv.cwd();
          try {
            inv.out().write(Files.readAllBytes(inv.cwd().resolve("in.json")));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return 0;
        };

    Verdict v = run("basics/file-argument", probe);

    // jq-ref printed the file compactly; the file is already compact, so copying it agrees.
    assertThat(v.kind()).isEqualTo(Verdict.Kind.PASS);
    assertThat(seenEnv).containsOnly(Map.entry("HOME", seenCwd[0].toString()), Map.entry("TZ", "UTC"), Map.entry("LC_ALL", "C"));
  }

  @Test
  void aCaseChangedAfterItsRecordingIsStale() {
    DiffCorpus.Entry e = basics("basics/compact-object");
    DiffCase edited = new DiffCase(e.c().id(), e.c().args(), "{\"b\":1}\n".getBytes(UTF_8), e.c().files(), e.c().env(), null, "000000000000");

    Verdict v = new DiffRunner(jqj(0, "{\"a\":1}\n", ""), scratch).run(edited, e.recording());

    assertThat(v.kind()).isEqualTo(Verdict.Kind.STALE);
    assertThat(v.detail()).contains("000000000000").contains(e.recording().def());
  }

  @Test
  void aCaseWithoutARecordingIsStale() {
    DiffCorpus.Entry e = basics("basics/compact-object");

    assertThat(new DiffRunner(jqj(0, "{\"a\":1}\n", ""), scratch).run(e.c(), null).kind()).isEqualTo(Verdict.Kind.STALE);
  }

  @Test
  void aStubWithoutARecordingIsFailNotStale() {
    DiffCorpus.Entry e = basics("basics/compact-object");

    Verdict v = new DiffRunner(Main::run, scratch).run(e.c(), null);

    assertThat(v.kind()).isEqualTo(Verdict.Kind.FAIL);
    assertThat(v.diff()).contains("no recording");
  }

  @Test
  void theDiffOfALongOrLateDifferenceShowsAWindowAroundIt() {
    Verdict longOutput = run("basics/compact-object", jqj(0, "x".repeat(1000), ""));
    assertThat(longOutput.diff()).contains("jqj    " + "x".repeat(200) + "... (1000 bytes)");

    Verdict late = run("basics/field-of-array", jqj(5, "", "jq: error (at <stdin>:0): Cannot index array with string (\"b\")\n"));
    assertThat(late.diff()).contains("first difference at byte 59").contains("jq-ref ...").contains("jqj    ...");
  }

  @Test
  void anExceptionOutOfTheEntryIsACrashUnlessItIsAStub() {
    Verdict crash =
        run(
            "basics/compact-object",
            inv -> {
              throw new IllegalStateException("boom");
            });
    assertThat(crash.kind()).isEqualTo(Verdict.Kind.CRASH);
    assertThat(crash.detail()).contains("IllegalStateException").contains("boom");

    Verdict stub =
        run(
            "basics/compact-object",
            inv -> {
              throw new IllegalStateException("jqj stub: the command line is not built yet");
            });
    assertThat(stub.kind()).isEqualTo(Verdict.Kind.FAIL);
  }

  @Test
  void noWorkingDirectoryIsLeftBehind() throws IOException {
    run("basics/file-argument", Main::run);
    run(
        "basics/file-argument",
        inv -> {
          throw new IllegalStateException("boom");
        });

    try (Stream<Path> left = Files.list(scratch)) {
      assertThat(left).isEmpty();
    }
  }
}
