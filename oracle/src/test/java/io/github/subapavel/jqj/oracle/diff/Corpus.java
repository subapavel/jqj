package io.github.subapavel.jqj.oracle.diff;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/** Where the differential corpus and the ratchet are, found from the class path so any working directory works. */
final class Corpus {

  private Corpus() {}

  /** {@code oracle/target/test-classes/diff}: the copied {@code oracle/src/test/resources/diff}. */
  static Path casesDir() {
    URL url = Corpus.class.getResource("/diff");
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The repository root: four levels above {@code oracle/target/test-classes/diff}. */
  static Path root() {
    return casesDir().getParent().getParent().getParent().getParent();
  }
}
