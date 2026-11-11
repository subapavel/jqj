package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The differential oracle report and the required cases, in the formats scripts/ratchet.java reads (ADR-0020). */
class DiffReportTest {

  @TempDir Path dir;

  @Test
  void theReportHoldsIdVerdictDetailAndDefOneLinePerCaseSortedById() throws IOException {
    Path report = dir.resolve("reports/differential-interpreter.tsv");

    DiffReport.merge(
        report,
        List.of(
            new DiffReport.Row("b/two", new Verdict(Verdict.Kind.FAIL, "rc 2, jq-ref 0", "diff"), "222222222222"),
            new DiffReport.Row("a/one", new Verdict(Verdict.Kind.PASS, "", ""), "111111111111")));

    assertThat(Files.readString(report, UTF_8)).isEqualTo("a/one\tPASS\t\t111111111111\nb/two\tFAIL\trc 2, jq-ref 0\t222222222222\n");
  }

  @Test
  void aRowWithALabelPutsItBetweenTheDetailAndTheDef() throws IOException {
    Path report = dir.resolve("json-interpreter.tsv");

    DiffReport.merge(report, List.of(new DiffReport.Row("json/y_x", new Verdict(Verdict.Kind.PASS, "", ""), "y_", "111111111111")));

    assertThat(Files.readString(report, UTF_8)).isEqualTo("json/y_x\tPASS\t\ty_\t111111111111\n");
  }

  @Test
  void aMergeReplacesTheRowsOfItsCasesAndKeepsTheOthers() throws IOException {
    Path report = dir.resolve("differential-interpreter.tsv");
    Files.writeString(report, "a/one\tFAIL\tx\t111111111111\nc/three\tPASS\t\t333333333333\n", UTF_8);

    DiffReport.merge(report, List.of(new DiffReport.Row("a/one", new Verdict(Verdict.Kind.PASS, "", ""), "111111111111")));

    assertThat(Files.readString(report, UTF_8)).isEqualTo("a/one\tPASS\t\t111111111111\nc/three\tPASS\t\t333333333333\n");
  }

  @Test
  void requiredCasesAreTheRatchetedIdsAndTheTicketsNamedIds() throws IOException {
    Path ratchet = dir.resolve("differential.txt");
    Files.writeString(ratchet, "# jq-ref 1.8.2 sha256 00\na/one\t111111111111\tinterpreter\n\n", UTF_8);
    Path named = dir.resolve("require.txt");
    Files.writeString(named, "b/two\n", UTF_8);

    assertThat(DiffReport.required(ratchet, named.toString())).containsExactlyInAnyOrder("a/one", "b/two");
  }

  @Test
  void withoutARatchetFileOrANamedListNoCaseIsRequired() {
    assertThat(DiffReport.required(dir.resolve("absent.txt"), null)).isEmpty();
  }

  @Test
  void aNamedRequireFileThatDoesNotExistFailsInsteadOfRequiringNothing() {
    assertThatThrownBy(() -> DiffReport.required(dir.resolve("absent.txt"), dir.resolve("typo.txt").toString()))
        .isInstanceOf(UncheckedIOException.class);
  }
}
