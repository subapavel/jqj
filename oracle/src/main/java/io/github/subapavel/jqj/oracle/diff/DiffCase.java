package io.github.subapavel.jqj.oracle.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A differential case (ADR-0020): a command line, its stdin, files and environment, never its expected output, which
 * only {@code scripts/oracle-record.sh} writes into the recording.
 *
 * <p>Keys: {@code arg} and {@code filter} are the command-line arguments in the order they appear (a case may put
 * options before or after the filter); {@code stdin} once; {@code file <name> <bytes>} and {@code env K=V}, each
 * repeatable. Without {@code stdin} the input is empty.
 *
 * @param files file name to content, written into the working directory of each run
 */
public record DiffCase(
    String id, List<String> args, byte[] stdin, Map<String, byte[]> files, Map<String, String> env, String def) {

  /** The cases of one {@code <family>.cases} file, in file order. */
  public static List<DiffCase> read(Path file) {
    List<DiffCase> cases = new ArrayList<>();
    for (CaseFormat.Block b : CaseFormat.read(file)) cases.add(of(file.getFileName().toString(), b));
    return cases;
  }

  /**
   * The case of one block.
   *
   * @throws IllegalArgumentException naming {@code where} and the line of an unknown key, a repeated {@code stdin}, an
   *     {@code env} without {@code =} or a malformed escape
   */
  public static DiffCase of(String where, CaseFormat.Block block) {
    List<String> args = new ArrayList<>();
    byte[] stdin = null;
    Map<String, byte[]> files = new LinkedHashMap<>();
    Map<String, String> env = new LinkedHashMap<>();
    for (CaseFormat.Line line : block.lines()) {
      String at = where + ":" + line.number() + ": ";
      try {
        switch (line.key()) {
          case "arg", "filter" -> args.add(new String(CaseFormat.unescape(line.value()), UTF_8));
          case "stdin" -> {
            if (stdin != null) throw new IllegalArgumentException("'stdin' given twice");
            stdin = CaseFormat.unescape(line.value());
          }
          case "file" -> {
            int space = line.value().indexOf(' ');
            String name = space < 0 ? line.value() : line.value().substring(0, space);
            files.put(name, CaseFormat.unescape(space < 0 ? "" : line.value().substring(space + 1)));
          }
          case "env" -> {
            String kv = new String(CaseFormat.unescape(line.value()), UTF_8);
            int eq = kv.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("'env' needs K=V");
            env.put(kv.substring(0, eq), kv.substring(eq + 1));
          }
          default ->
              throw new IllegalArgumentException(
                  "unknown key '" + line.key() + "': a case holds the command line only; its expected output is"
                      + " recorded by scripts/oracle-record.sh, never written");
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(at + "case " + block.id() + ": " + e.getMessage(), e);
      }
    }
    return new DiffCase(block.id(), List.copyOf(args), stdin == null ? new byte[0] : stdin, files, env, block.def());
  }

  /** The environment of a run: exactly {@code HOME=<home> TZ=UTC LC_ALL=C} plus the case's {@code env} (ADR-0020). */
  public Map<String, String> environment(Path home) {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("HOME", home.toString());
    all.put("TZ", "UTC");
    all.put("LC_ALL", "C");
    all.putAll(env);
    return all;
  }
}
