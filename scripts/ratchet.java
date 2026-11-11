// The ratchet (ADR-0020, ADR-0014): what passes, per oracle and per module. Single-file source launcher, no
// dependencies:
//
//   java scripts/ratchet.java check                  ticket gate: exit 1 if a ratcheted case or percentage fails
//   java scripts/ratchet.java regen --oracles        after every merge: the oracle pass lists only
//   java scripts/ratchet.java regen --all            spec gate: every report required, no drop allowed
//   java scripts/ratchet.java floor-mutation <m>...  PIT score of the modules against quality/floors.tsv
//
// Environment: RATCHET_ROOT (default: the current directory), RATCHET_JMH (JMH json result, default
// bench/jmh/target/jmh-result.json), RATCHET_MACHINE (the machine label of bench.tsv lines).
// Exit codes: 0 ok, 1 a failure or a refused drop, 2 usage. Format: ratchet/README.md.

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ratchet {

  /** Where a column of a pass-list line comes from: the id, the report file's suffix, or report column n. */
  record Col(String name, int reportColumn) {
    static final int ID = -1;
    static final int SUFFIX = -2;
  }

  record Oracle(String name, List<Col> cols) {
    boolean hasSuffix() {
      return cols.stream().anyMatch(c -> c.reportColumn() == Col.SUFFIX);
    }
  }

  static final List<Oracle> ORACLES =
      List.of(
          oracle("jqtest", "id", "backend"),
          oracle("differential", "id", "def:3", "backend"),
          oracle("compile", "id", "def:3", "mode"),
          oracle("backend", "id", "def:3"),
          oracle("json", "id", "label:3", "def:4"));

  static Oracle oracle(String name, String... cols) {
    List<Col> list = new ArrayList<>();
    for (String c : cols) {
      if (c.equals("id")) list.add(new Col("id", Col.ID));
      else if (c.equals("backend") || c.equals("mode")) list.add(new Col(c, Col.SUFFIX));
      else list.add(new Col(c.substring(0, c.indexOf(':')), Integer.parseInt(c.substring(c.indexOf(':') + 1))));
    }
    return new Oracle(name, list);
  }

  /** A row of an oracle report; extras are the report columns from the fourth on. */
  record Row(String verdict, String[] cells) {}

  record Floor(String module, boolean classC, Integer lines, Integer branches, Integer mutation) {}

  static final Path root = Path.of(System.getenv().getOrDefault("RATCHET_ROOT", ".")).toAbsolutePath();
  static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    String cmd = args.length == 0 ? "" : args[0];
    int rc;
    switch (cmd) {
      case "check" -> rc = args.length == 1 ? check() : usage();
      case "regen" -> rc = args.length == 2 && args[1].equals("--oracles") ? regen(false)
          : args.length == 2 && args[1].equals("--all") ? regen(true) : usage();
      case "floor-mutation" -> rc = args.length >= 2 ? floorMutation(List.of(args).subList(1, args.length)) : usage();
      default -> rc = usage();
    }
    System.exit(rc);
  }

  static int usage() {
    System.err.println("usage: java scripts/ratchet.java check | regen --oracles | regen --all | floor-mutation <module>...");
    return 2;
  }

  static void fail(String message) {
    failures.add(message);
    System.out.println("FAIL " + message);
  }

  static int finish(String what) {
    if (failures.isEmpty()) {
      System.out.println(what + ": ok");
      return 0;
    }
    System.out.println(what + ": " + failures.size() + " failure(s)");
    return 1;
  }

  // ---------------------------------------------------------------- check

  static int check() throws IOException {
    for (Oracle o : ORACLES) checkOracle(o);
    List<Floor> floors = floors();
    Map<String, int[]> cov = coverageOf(floors);
    checkCoverage(floors, cov);
    checkMutation(floors);
    checkBench();
    return finish("check");
  }

  // --- oracles

  static Map<String, Row> reports(Oracle o) throws IOException {
    Map<String, Row> rows = new LinkedHashMap<>();
    Path dir = root.resolve("oracle/target/oracle-reports");
    if (!Files.isDirectory(dir)) return rows;
    try (var files = Files.list(dir)) {
      for (Path f : (Iterable<Path>) files.sorted()::iterator) {
        String n = f.getFileName().toString();
        if (!n.startsWith(o.name() + "-") || !n.endsWith(".tsv")) continue;
        String suffix = n.substring(o.name().length() + 1, n.length() - 4);
        for (String line : lines(f)) {
          if (line.isEmpty()) continue;
          String[] c = line.split("\t", -1);
          if (c.length < 2) continue;
          rows.put(o.hasSuffix() ? c[0] + "\t" + suffix : c[0], new Row(c[1], c));
        }
      }
    }
    return rows;
  }

  static List<String> lines(Path f) throws IOException {
    String s = Files.readString(f, StandardCharsets.UTF_8);
    List<String> out = new ArrayList<>();
    for (String l : s.split("\n", -1)) out.add(l);
    if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
    return out;
  }

  static String sha256(Path f) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The ratcheted lines of an oracle: header lines and entries. */
  record PassList(List<String> headers, List<String> entries) {}

  static PassList passList(Oracle o) throws IOException {
    Path f = root.resolve("ratchet/oracle/" + o.name() + ".txt");
    List<String> headers = new ArrayList<>(), entries = new ArrayList<>();
    if (Files.exists(f)) {
      for (String l : lines(f)) {
        if (l.startsWith("#")) headers.add(l);
        else if (!l.isEmpty()) entries.add(l);
      }
    }
    return new PassList(headers, entries);
  }

  /** True if the entry's source file is among the headers and its sha now differs from the header's. */
  static boolean retirable(String id, List<String> headers) throws IOException {
    for (String h : headers) {
      String[] t = h.split(" ");
      if (t.length >= 5 && t[1].equals("source")) {
        Path p = root.resolve(t[2]);
        String base = p.getFileName().toString();
        if (id.startsWith(base + "#") && Files.exists(p) && !sha256(p).equals(t[t.length - 1])) return true;
      }
    }
    return false;
  }

  /** Checks one pass list; returns the entries that survive (not retired). */
  static List<String> checkOracle(Oracle o) throws IOException {
    PassList pl = passList(o);
    Map<String, Row> rep = reports(o);
    List<String> survivors = new ArrayList<>();
    for (String entry : pl.entries()) {
      String[] c = entry.split("\t", -1);
      String id = c[0];
      String key = id;
      for (int i = 0; i < o.cols().size(); i++) {
        if (o.cols().get(i).reportColumn() == Col.SUFFIX) key = id + "\t" + c[i];
      }
      Row r = rep.get(key);
      if (r == null) {
        if (retirable(id, pl.headers())) {
          System.out.println("warning " + o.name() + ": " + id + " retired, its source file changed");
        } else {
          survivors.add(entry);
          fail(o.name() + ": " + key.replace('\t', ' ') + " is missing from the report");
        }
        continue;
      }
      survivors.add(entry);
      if (!r.verdict().equals("PASS")) {
        fail(o.name() + ": " + key.replace('\t', ' ') + " is " + r.verdict());
        continue;
      }
      for (int i = 0; i < o.cols().size() && i < c.length; i++) {
        Col col = o.cols().get(i);
        if (col.reportColumn() < 0) continue;
        String now = col.reportColumn() < r.cells().length ? r.cells()[col.reportColumn()] : "";
        if (!now.equals(c[i])) fail(o.name() + ": " + id + " " + col.name() + " changed (" + c[i] + " -> " + now + ")");
      }
    }
    return survivors;
  }

  // --- floors, coverage

  static List<Floor> floors() throws IOException {
    List<Floor> out = new ArrayList<>();
    Path f = root.resolve("quality/floors.tsv");
    if (!Files.exists(f)) return out;
    List<String> ls = lines(f);
    for (String l : ls.subList(Math.min(1, ls.size()), ls.size())) {
      String[] c = l.split("\t", -1);
      if (c.length < 5) continue;
      out.add(new Floor(c[0], c[1].equals("C"), num(c[2]), num(c[3]), num(c[4])));
    }
    return out;
  }

  static Integer num(String s) {
    return s.equals("-") || s.isEmpty() ? null : Integer.valueOf(s);
  }

  static int pct(long covered, long missed) {
    long total = covered + missed;
    return total == 0 ? 100 : (int) (100 * covered / total);
  }

  /** Lines % and branches % per module, from JaCoCo's csv; a module without a report is absent. */
  static Map<String, int[]> coverageOf(List<Floor> floors) throws IOException {
    Map<String, int[]> out = new TreeMap<>();
    Map<String, Boolean> classC = new TreeMap<>();
    for (Floor f : floors) classC.put(f.module(), f.classC());
    for (String m : table("coverage.tsv").keySet()) classC.putIfAbsent(m, false);
    for (var e : classC.entrySet()) {
      String m = e.getKey();
      // Class-C modules are measured by every test in the reactor: oracle's aggregate report (ADR-0014).
      Path csv = e.getValue() ? root.resolve("oracle/target/site/jacoco-aggregate/jacoco.csv")
          : root.resolve(m + "/target/site/jacoco/jacoco.csv");
      if (!Files.exists(csv)) continue;
      List<String> ls = lines(csv);
      List<String> head = List.of(ls.get(0).split(",", -1));
      int g = head.indexOf("GROUP"), bm = head.indexOf("BRANCH_MISSED"), bc = head.indexOf("BRANCH_COVERED"),
          lm = head.indexOf("LINE_MISSED"), lc = head.indexOf("LINE_COVERED");
      long[] s = new long[4];
      boolean any = false;
      for (String l : ls.subList(1, ls.size())) {
        String[] c = l.split(",", -1);
        if (e.getValue() && !c[g].equals(m)) continue;
        any = true;
        s[0] += Long.parseLong(c[lm]);
        s[1] += Long.parseLong(c[lc]);
        s[2] += Long.parseLong(c[bm]);
        s[3] += Long.parseLong(c[bc]);
      }
      if (any) out.put(m, new int[] {pct(s[1], s[0]), pct(s[3], s[2])});
    }
    return out;
  }

  /** A two-or-more column table file of ratchet/ keyed by its first column, header dropped. */
  static Map<String, String[]> table(String name) throws IOException {
    Map<String, String[]> out = new TreeMap<>();
    Path f = root.resolve("ratchet/" + name);
    if (!Files.exists(f)) return out;
    List<String> ls = lines(f);
    for (String l : ls.subList(Math.min(1, ls.size()), ls.size())) {
      if (l.isEmpty()) continue;
      String[] c = l.split("\t", -1);
      out.put(name.equals("bench.tsv") ? c[0] + "\t" + c[1] : c[0], c);
    }
    return out;
  }

  static void checkCoverage(List<Floor> floors, Map<String, int[]> cov) throws IOException {
    Map<String, String[]> old = table("coverage.tsv");
    TreeSet<String> modules = new TreeSet<>(old.keySet());
    for (Floor f : floors) modules.add(f.module());
    for (String m : modules) {
      int[] a = cov.get(m);
      if (a == null) {
        System.out.println("NOT_RUN coverage " + m + ": no JaCoCo report");
        continue;
      }
      Floor f = floors.stream().filter(x -> x.module().equals(m)).findFirst().orElse(null);
      String[] o = old.get(m);
      compare("coverage " + m + " lines", a[0], f == null ? null : f.lines(), o == null ? null : Integer.valueOf(o[1]));
      compare("coverage " + m + " branches", a[1], f == null ? null : f.branches(), o == null ? null : Integer.valueOf(o[2]));
    }
  }

  static void compare(String what, int actual, Integer floor, Integer ratcheted) {
    if (floor != null && actual < floor) fail(what + " " + actual + " % is below the floor " + floor + " %");
    if (ratcheted != null && actual < ratcheted) fail(what + " " + actual + " % is below the ratchet " + ratcheted + " %");
  }

  // --- mutation

  static final Pattern MUTATION = Pattern.compile("<mutation\\b[^>]*>");
  static final Pattern STATUS = Pattern.compile("status='([A-Z_]+)'");

  /** {survivors, generated} of a module's PIT report, or null without one. Killed means KILLED, TIMED_OUT or MEMORY_ERROR. */
  static int[] pit(String module) throws IOException {
    Path f = root.resolve(module + "/target/pit-reports/mutations.xml");
    if (!Files.exists(f)) return null;
    int generated = 0, killed = 0;
    Matcher m = MUTATION.matcher(Files.readString(f, StandardCharsets.UTF_8));
    while (m.find()) {
      generated++;
      Matcher s = STATUS.matcher(m.group());
      if (s.find() && List.of("KILLED", "TIMED_OUT", "MEMORY_ERROR").contains(s.group(1))) killed++;
    }
    return new int[] {generated - killed, generated};
  }

  static int score(int[] p) {
    return pct(p[1] - p[0], p[0]);
  }

  static void checkMutation(List<Floor> floors) throws IOException {
    Map<String, String[]> old = table("mutation.tsv");
    TreeSet<String> modules = new TreeSet<>(old.keySet());
    for (Floor f : floors) if (f.classC()) modules.add(f.module());
    for (String m : modules) {
      int[] p = pit(m);
      if (p == null) {
        System.out.println("NOT_RUN mutation " + m + ": no PIT report");
        continue;
      }
      Floor f = floors.stream().filter(x -> x.module().equals(m)).findFirst().orElse(null);
      String[] o = old.get(m);
      compare("mutation " + m + " score", score(p), f == null ? null : f.mutation(),
          o == null ? null : Integer.valueOf(pct(Long.parseLong(o[2]) - Long.parseLong(o[1]), Long.parseLong(o[1]))));
    }
  }

  static int floorMutation(List<String> modules) throws IOException {
    List<Floor> floors = floors();
    for (String m : modules) {
      int[] p = pit(m);
      if (p == null) {
        System.out.println("NOT_RUN mutation " + m + ": no PIT report");
        continue;
      }
      Floor f = floors.stream().filter(x -> x.module().equals(m)).findFirst().orElse(null);
      System.out.println("mutation " + m + ": " + score(p) + " % (" + p[0] + " surviving of " + p[1] + ")");
      compare("mutation " + m + " score", score(p), f == null ? null : f.mutation(), null);
    }
    return finish("floor-mutation");
  }

  // --- bench

  record Bench(String scenario, BigDecimal score, String unit) {}

  static List<Bench> jmh() throws IOException {
    Path f = Path.of(System.getenv().getOrDefault("RATCHET_JMH", root.resolve("bench/jmh/target/jmh-result.json").toString()));
    if (!Files.exists(f)) return null;
    Object doc = new Json(Files.readString(f, StandardCharsets.UTF_8)).value();
    List<Bench> out = new ArrayList<>();
    for (Object o : (List<?>) doc) {
      Map<?, ?> m = (Map<?, ?>) o;
      String name = (String) m.get("benchmark");
      name = name.substring(name.lastIndexOf('.') + 1);
      if (m.get("params") instanceof Map<?, ?> ps) {
        StringBuilder b = new StringBuilder(name);
        new TreeMap<>(ps).forEach((k, v) -> b.append('/').append(k).append('=').append(v));
        name = b.toString();
      }
      Map<?, ?> pm = (Map<?, ?>) m.get("primaryMetric");
      out.add(new Bench(name, (BigDecimal) pm.get("score"), (String) pm.get("scoreUnit")));
    }
    return out;
  }

  static String machine() {
    return System.getenv("RATCHET_MACHINE");
  }

  static String plain(BigDecimal b) {
    BigDecimal r = b.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    return r.scale() < 0 ? r.setScale(0).toPlainString() : r.toPlainString();
  }

  /** A line with an empty tolerance% is skipped (ADR-0019, ADR-0023). Lower is better: the score is jqj's time. */
  static void checkBench() throws IOException {
    Map<String, String[]> old = table("bench.tsv");
    List<Bench> now = null;
    for (String[] line : old.values()) {
      if (line.length < 5 || line[4].isEmpty()) continue;
      if (now == null) now = jmh();
      if (now == null || !line[1].equals(machine())) {
        System.out.println("NOT_RUN bench " + line[0] + " on " + line[1] + ": no result for that machine");
        continue;
      }
      for (Bench b : now) {
        if (!b.scenario().equals(line[0])) continue;
        BigDecimal limit = new BigDecimal(line[2]).multiply(BigDecimal.ONE.add(new BigDecimal(line[4]).movePointLeft(2)));
        if (b.score().compareTo(limit) > 0) {
          fail("bench " + line[0] + " " + plain(b.score()) + " " + b.unit() + " is slower than " + line[2] + " by more than " + line[4] + " %");
        }
      }
    }
  }

  // ---------------------------------------------------------------- regen

  static int regen(boolean all) throws IOException {
    Map<String, List<String>> writes = new TreeMap<>();
    List<String> report = new ArrayList<>();

    for (Oracle o : ORACLES) {
      List<String> survivors = checkOracle(o);
      Map<String, Row> rep = reports(o);
      PassList pl = passList(o);
      if (pl.entries().isEmpty() && rep.isEmpty()) continue;
      TreeSet<String> body = new TreeSet<>(survivors);
      int before = body.size();
      for (var e : rep.entrySet()) {
        if (!e.getValue().verdict().equals("PASS")) continue;
        body.add(entryOf(o, e.getKey(), e.getValue()));
      }
      List<String> out = new ArrayList<>();
      for (String h : pl.headers()) out.add(refreshHeader(h));
      out.addAll(body);
      writes.put("oracle/" + o.name() + ".txt", out);
      report.add(o.name() + ": +" + (body.size() - before) + " -" + (pl.entries().size() - survivors.size()));
    }

    if (all) {
      List<Floor> floors = floors();
      Map<String, int[]> cov = coverageOf(floors);
      Map<String, String[]> oldCov = table("coverage.tsv");
      List<String> covOut = new ArrayList<>(List.of("module\tlines %\tbranches %"));
      TreeSet<String> covBody = new TreeSet<>();
      for (Floor f : floors) {
        int[] a = cov.get(f.module());
        if (a == null) {
          fail("coverage " + f.module() + ": no JaCoCo report; regen --all needs every report");
          continue;
        }
        String[] o = oldCov.get(f.module());
        compare("coverage " + f.module() + " lines", a[0], f.lines(), o == null ? null : Integer.valueOf(o[1]));
        compare("coverage " + f.module() + " branches", a[1], f.branches(), o == null ? null : Integer.valueOf(o[2]));
        covBody.add(f.module() + "\t" + a[0] + "\t" + a[1]);
        report.add("coverage " + f.module() + ": lines " + a[0] + " %, branches " + a[1] + " %");
      }
      covOut.addAll(covBody);
      writes.put("coverage.tsv", covOut);

      Map<String, String[]> oldMut = table("mutation.tsv");
      List<String> mutOut = new ArrayList<>(List.of("module\tsurviving mutants\tgenerated mutants"));
      TreeSet<String> mutBody = new TreeSet<>();
      for (Floor f : floors) {
        if (!f.classC()) continue;
        int[] p = pit(f.module());
        if (p == null) {
          fail("mutation " + f.module() + ": no PIT report; regen --all needs every report");
          continue;
        }
        String[] o = oldMut.get(f.module());
        compare("mutation " + f.module() + " score", score(p), f.mutation(),
            o == null ? null : Integer.valueOf(pct(Long.parseLong(o[2]) - Long.parseLong(o[1]), Long.parseLong(o[1]))));
        mutBody.add(f.module() + "\t" + p[0] + "\t" + p[1]);
        report.add("mutation " + f.module() + ": " + p[0] + " surviving of " + p[1]);
      }
      mutOut.addAll(mutBody);
      writes.put("mutation.tsv", mutOut);

      List<Bench> now = jmh();
      if (now == null) fail("bench: no JMH result; regen --all needs every report");
      else if (machine() == null || machine().isEmpty()) fail("bench: RATCHET_MACHINE is not set");
      else {
        Map<String, String[]> oldBench = table("bench.tsv");
        Map<String, String> lines = new TreeMap<>();
        for (var e : oldBench.entrySet()) lines.put(e.getKey(), String.join("\t", e.getValue()));
        for (Bench b : now) {
          String key = b.scenario() + "\t" + machine();
          String[] o = oldBench.get(key);
          String line = key + "\t" + plain(b.score()) + "\t" + b.unit() + "\t";
          if (o != null && o.length >= 5 && !o[4].isEmpty()) {
            BigDecimal tol = new BigDecimal(o[4]).movePointLeft(2), base = new BigDecimal(o[2]);
            if (b.score().compareTo(base.multiply(BigDecimal.ONE.add(tol))) > 0) {
              fail("bench " + b.scenario() + " " + plain(b.score()) + " is slower than " + o[2] + " by more than " + o[4] + " %");
              continue;
            }
            // The baseline moves only on a gain beyond its tolerance.
            line = b.score().compareTo(base.multiply(BigDecimal.ONE.subtract(tol))) < 0 ? line + o[4] : String.join("\t", o);
          }
          lines.put(key, line);
          report.add("bench " + b.scenario() + ": " + plain(b.score()) + " " + b.unit());
        }
        List<String> out = new ArrayList<>(List.of("scenario\tmachine\tscore\tunit\ttolerance%"));
        out.addAll(lines.values());
        writes.put("bench.tsv", out);
      }
    }

    if (!failures.isEmpty()) {
      System.out.println("regen refused: nothing written");
      return finish("regen");
    }
    for (var e : writes.entrySet()) {
      Path f = root.resolve("ratchet/" + e.getKey());
      Files.createDirectories(f.getParent());
      Files.writeString(f, e.getValue().isEmpty() ? "" : String.join("\n", e.getValue()) + "\n", StandardCharsets.UTF_8);
    }
    report.forEach(System.out::println);
    return finish("regen");
  }

  /** The pass-list line of a report row: the columns of the oracle's format, tab separated. */
  static String entryOf(Oracle o, String key, Row r) {
    String suffix = key.contains("\t") ? key.substring(key.indexOf('\t') + 1) : "";
    List<String> cells = new ArrayList<>();
    for (Col c : o.cols()) {
      cells.add(switch (c.reportColumn()) {
        case Col.ID -> r.cells()[0];
        case Col.SUFFIX -> suffix;
        default -> c.reportColumn() < r.cells().length ? r.cells()[c.reportColumn()] : "";
      });
    }
    return String.join("\t", cells);
  }

  /** A "# source <path> sha256 <hex>" header takes the file's current sha; other headers stay. */
  static String refreshHeader(String h) throws IOException {
    String[] t = h.split(" ");
    if (t.length >= 5 && t[1].equals("source") && Files.exists(root.resolve(t[2]))) {
      return "# source " + t[2] + " sha256 " + sha256(root.resolve(t[2]));
    }
    return h;
  }

  // ---------------------------------------------------------------- JSON (JMH results only)

  static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    Object value() {
      ws();
      char c = s.charAt(i);
      if (c == '{') {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        do {
          ws();
          String k = string();
          ws();
          i++; // ':'
          m.put(k, value());
          ws();
        } while (s.charAt(i++) == ',');
        return m;
      }
      if (c == '[') {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        do {
          l.add(value());
          ws();
        } while (s.charAt(i++) == ',');
        return l;
      }
      if (c == '"') return string();
      int start = i;
      while (i < s.length() && ",]} \n\r\t".indexOf(s.charAt(i)) < 0) i++;
      String lit = s.substring(start, i);
      return switch (lit) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        case "null" -> "null";
        case "NaN" -> BigDecimal.ZERO;
        default -> new BigDecimal(lit);
      };
    }

    private String string() {
      StringBuilder b = new StringBuilder();
      i++; // opening quote
      while (s.charAt(i) != '"') {
        char c = s.charAt(i++);
        if (c == '\\') {
          char e = s.charAt(i++);
          b.append(switch (e) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case 'b' -> '\b'; case 'f' -> '\f'; default -> e; });
        } else b.append(c);
      }
      i++;
      return b.toString();
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
  }
}
