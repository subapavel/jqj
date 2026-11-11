package io.github.subapavel.jqj.oracle.json;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.diff.DiffCorpus;
import io.github.subapavel.jqj.oracle.diff.DiffReport;
import io.github.subapavel.jqj.oracle.diff.DiffRunner;
import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The json oracle (ADR-0020): one dynamic test per required JSONTestSuite case, sorted by id, which must PASS against
 * jq-ref's recording. Every verdict goes into the json report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonOracleTest {

  private final List<DiffReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> requiredCases(@TempDir Path scratch) {
    Set<String> required = JsonCorpus.required();
    DiffRunner runner = JsonCorpus.runner(scratch);
    return DiffCorpus.load(JsonCorpus.casesDir()).stream()
        .filter(e -> required.contains(e.c().id()))
        .map(
            e ->
                dynamicTest(
                    e.c().id(),
                    () -> {
                      Verdict v = runner.run(e.c(), e.recording());
                      rows.add(JsonCorpus.row(e, v));
                      if (v.kind() != Verdict.Kind.PASS) fail(v.diff());
                    }));
  }

  @AfterAll
  void writeReport() {
    JsonCorpus.report(rows);
  }
}
