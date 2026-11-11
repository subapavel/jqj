package io.github.subapavel.jqj.oracle.jqtest;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

/**
 * The jqtest oracle (ADR-0020): one dynamic test per required case of jq's test files, sorted by id, which must PASS
 * through the public API. Every verdict goes into the jqtest report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JqTestOracleTest {

  private final List<JqTestReport.Row> rows = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> requiredCases() {
    Set<String> required = JqTestCorpus.required();
    JqTestRunner runner = JqTestCorpus.runner();
    return JqTestCorpus.cases().stream()
        .filter(c -> required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      Verdict v = runner.run(c);
                      rows.add(new JqTestReport.Row(c.id(), v));
                      if (v.kind() != Verdict.Kind.PASS) fail(v.diff());
                    }));
  }

  @AfterAll
  void writeReport() {
    JqTestCorpus.report(rows);
  }
}
