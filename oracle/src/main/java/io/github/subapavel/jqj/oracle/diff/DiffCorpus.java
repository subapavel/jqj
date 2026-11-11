package io.github.subapavel.jqj.oracle.diff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Every differential case of a directory of {@code <family>.cases} files, with its recording if it has one. */
public final class DiffCorpus {

  /** A case and its recording from the sibling {@code <family>.rec}; null when the case was never recorded. */
  public record Entry(DiffCase c, Recording recording) {}

  private DiffCorpus() {}

  /** The cases of every {@code .cases} file in {@code dir}, sorted by id. */
  public static List<Entry> load(Path dir) {
    List<Entry> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".cases")).toList()) {
        Map<String, Recording> recordings = Recording.read(dir.resolve(CaseFormat.family(f) + ".rec"));
        for (DiffCase c : DiffCase.read(f)) out.add(new Entry(c, recordings.get(c.id())));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    out.sort(Comparator.comparing(e -> e.c().id()));
    return out;
  }
}
