package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Where the jqtest oracle's files and its ratchet are (ADR-0020): jq 1.8.2's seven test files. */
final class JqTestCorpus {

  private JqTestCorpus() {}

  /** {@code oracle/target/test-classes/jqtest}: the copied {@code oracle/src/test/resources/jqtest}. */
  static Path dir() {
    URL url = JqTestCorpus.class.getResource("/jqtest");
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The {@code .test} files, sorted by name. */
  static List<Path> files() {
    try (Stream<Path> s = Files.list(dir())) {
      return s.filter(p -> p.getFileName().toString().endsWith(".test")).sorted(Comparator.comparing(Path::toString)).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Every case of every file, sorted by id. A file that is not UTF-8 fails to read. */
  static List<JqTestCase> cases() {
    List<JqTestCase> all = new ArrayList<>();
    for (Path f : files()) all.addAll(parse(f));
    all.sort(Comparator.comparing(JqTestCase::id));
    return all;
  }

  /** The number of cases per file name. */
  static Map<String, Integer> countsPerFile() {
    Map<String, Integer> counts = new TreeMap<>();
    for (Path f : files()) counts.put(f.getFileName().toString(), parse(f).size());
    return counts;
  }

  private static List<JqTestCase> parse(Path f) {
    try {
      return JqTestFile.parse(f.getFileName().toString(), Files.readString(f, UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
