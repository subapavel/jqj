package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.Verdict;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The jqtest oracle report {@code oracle/target/oracle-reports/jqtest-<backend>.tsv} as {@code scripts/ratchet.java}
 * reads it (ADR-0020). Columns: {@code id}, verdict, detail. The required ids come from
 * {@link io.github.subapavel.jqj.oracle.diff.DiffReport#required}, whose format is the same.
 */
public final class JqTestReport {

  /** One case's line of the report. */
  public record Row(String id, Verdict verdict) {
    String line() {
      return id + "\t" + verdict.kind() + "\t" + verdict.detail();
    }
  }

  private JqTestReport() {}

  /** Writes {@code rows} into the report: each replaces the line of its id, other lines stay; sorted by id. */
  public static void merge(Path report, List<Row> rows) {
    try {
      Map<String, String> lines = new TreeMap<>();
      if (Files.exists(report)) {
        for (String l : Files.readAllLines(report, UTF_8)) lines.put(l.split("\t", 2)[0], l);
      }
      for (Row r : rows) lines.put(r.id(), r.line());
      Files.createDirectories(report.getParent());
      StringBuilder b = new StringBuilder();
      for (String l : lines.values()) b.append(l).append('\n');
      Files.writeString(report, b, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
