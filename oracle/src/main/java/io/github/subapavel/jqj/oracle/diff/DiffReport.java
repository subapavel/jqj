package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The differential oracle report {@code oracle/target/oracle-reports/differential-<backend>.tsv} and the required
 * cases, as {@code scripts/ratchet.java} reads and writes them (ADR-0020). Report columns: {@code id}, verdict,
 * detail, {@code def}.
 */
public final class DiffReport {

  /**
   * One case's line of the report: {@code id}, verdict, detail, then {@code label} if there is one (the json
   * oracle's), then {@code def}.
   *
   * @param label null for the oracles that have none
   */
  public record Row(String id, Verdict verdict, String label, String def) {
    public Row(String id, Verdict verdict, String def) {
      this(id, verdict, null, def);
    }

    String line() {
      return id + "\t" + verdict.kind() + "\t" + verdict.detail() + "\t" + (label == null ? "" : label + "\t") + def;
    }
  }

  private DiffReport() {}

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

  /**
   * The required case ids: the first column of the ratchet's {@code differential.txt}, if it exists, and of the
   * ticket's {@code -Doracle.require=<file>}, if given. Blank and {@code #} lines are skipped.
   *
   * @throws UncheckedIOException when a named require file cannot be read
   */
  public static Set<String> required(Path ratchet, String requireFile) {
    Set<String> ids = new HashSet<>();
    if (Files.exists(ratchet)) ids(ratchet, ids);
    if (requireFile != null) ids(Path.of(requireFile), ids);
    return ids;
  }

  private static void ids(Path file, Set<String> into) {
    try {
      for (String l : Files.readAllLines(file, UTF_8)) {
        if (l.isEmpty() || l.startsWith("#")) continue;
        into.add(l.split("\t", 2)[0]);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
