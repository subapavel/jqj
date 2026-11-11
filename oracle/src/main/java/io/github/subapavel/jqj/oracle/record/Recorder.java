package io.github.subapavel.jqj.oracle.record;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.CaseDirectory;
import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Recording;
import io.github.subapavel.jqj.oracle.gen.ValuesV1;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The record command of the differential oracle (ADR-0020), started by {@code scripts/oracle-record.sh}:
 *
 * <pre>
 *   Recorder &lt;family.cases&gt;...          writes &lt;family&gt;.rec beside each case file
 *   Recorder --verify &lt;family.rec&gt;...    re-records in memory and fails on any byte difference
 *   Recorder --generate values-v1 &lt;from&gt; &lt;to&gt; &lt;diff-dir&gt;
 *                                        writes gen/values-v1.cases and .rec below diff-dir for the seeds [from, to)
 * </pre>
 *
 * Each case runs twice in a fresh directory with its files and an environment of exactly {@code HOME TZ LC_ALL} plus
 * its {@code env}, under a 10 s timeout; two runs that differ reject the case, and so does a timeout. A generated
 * Seed whose run gives rc 3 or times out is instead a Dropped seed: a {@code # dropped <seed> rc3|timeout} line in
 * the case file's header (ADR-0025). Exit codes: 0 ok, 1 a rejected case, a refused file or a difference, 2 usage.
 */
public final class Recorder {

  private static final Path JQ_REF = Path.of("/usr/local/bin/jq-ref");
  private static final long TIMEOUT_SECONDS = 10;

  /** The reference binary as {@code --generate} sees it; tests stand a fake in for jq-ref. */
  interface Reference {

    /** The recording header, {@code # jq-<version> sha256 <hex>}. */
    String header() throws IOException, InterruptedException;

    /** The outcome of one case, or null when it ran over the timeout. */
    Outcome run(DiffCase c) throws IOException, InterruptedException;
  }

  private static final Reference JQ_REF_BINARY =
      new Reference() {
        @Override
        public String header() throws IOException, InterruptedException {
          return Recorder.header();
        }

        @Override
        public Outcome run(DiffCase c) throws IOException, InterruptedException {
          return runReference(c);
        }
      };

  /**
   * A generated family: the text of its case file and the recordings of the seeds that were not dropped.
   *
   * @param cases the text of {@code gen/<generator>.cases}
   */
  record Family(String cases, List<Recording> recordings) {

    /** Writes {@code gen/values-v1.cases} and {@code .rec} below {@code diff}. */
    void write(Path diff, String header) throws IOException {
      Path base = diff.resolve(ValuesV1.FAMILY);
      Files.createDirectories(base.getParent());
      Files.writeString(Path.of(base + ".cases"), cases, UTF_8);
      Files.write(Path.of(base + ".rec"), Recording.file(header, recordings));
    }
  }

  private Recorder() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    System.exit(run(List.of(args)));
  }

  static int run(List<String> args) throws IOException, InterruptedException {
    if (!args.isEmpty() && args.get(0).equals("--generate")) return generate(args.subList(1, args.size()), JQ_REF_BINARY);
    boolean verify = !args.isEmpty() && args.get(0).equals("--verify");
    List<String> files = verify ? args.subList(1, args.size()) : args;
    String extension = verify ? ".rec" : ".cases";
    if (files.isEmpty() || !files.stream().allMatch(f -> f.endsWith(extension))) {
      System.err.println("usage: scripts/oracle-record.sh <family.cases>... | --verify <family.rec>...");
      return 2;
    }
    String header = header();
    int rc = 0;
    for (String f : files) {
      Path file = Path.of(f);
      String base = f.substring(0, f.length() - extension.length());
      Path cases = Path.of(base + ".cases");
      Path rec = Path.of(base + ".rec");
      byte[] fresh;
      try {
        fresh = Recording.file(header, record(cases));
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        rc = 1;
        continue;
      }
      if (!verify) {
        Files.write(rec, fresh);
        System.out.println("recorded " + rec);
      } else if (differs(file, fresh)) {
        rc = 1;
      } else {
        System.out.println("verified " + file);
      }
    }
    return rc;
  }

  /** {@code # jq-<version> sha256 <hex>} of the reference binary. */
  private static String header() throws IOException, InterruptedException {
    Process p = new ProcessBuilder(JQ_REF.toString(), "--version").redirectErrorStream(true).start();
    String version = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
    p.waitFor();
    try {
      byte[] sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(JQ_REF));
      return "# " + version + " sha256 " + HexFormat.of().formatHex(sha);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK has SHA-256", e);
    }
  }

  /** {@code --generate values-v1 <from> <to> <diff-dir>}, with {@code 0 <= from < to}. */
  static int generate(List<String> args, Reference reference) throws IOException, InterruptedException {
    long[] range = range(args);
    if (range == null) {
      System.err.println("usage: scripts/oracle-record.sh --generate " + ValuesV1.NAME + " <from> <to>, 0 <= from < to");
      return 2;
    }
    long from = range[0];
    long to = range[1];
    String header = reference.header();
    // Two runs that differ throw out of main before anything is written, which exits 1.
    Family family = generate(from, to, reference);
    family.write(Path.of(args.get(3)), header);
    int dropped = (int) (to - from) - family.recordings().size();
    System.out.println("recorded " + family.recordings().size() + " seeds of " + ValuesV1.NAME + ", dropped " + dropped);
    return 0;
  }

  /** {@code {from, to}} of {@code values-v1 <from> <to> <diff-dir>} with {@code 0 <= from < to}; otherwise null. */
  private static long[] range(List<String> args) {
    if (args.size() != 4 || !args.get(0).equals(ValuesV1.NAME)) return null;
    try {
      long from = Long.parseLong(args.get(1));
      long to = Long.parseLong(args.get(2));
      return from >= 0 && from < to ? new long[] {from, to} : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Records the seeds [{@code from}, {@code to}) of values-v1: each runs twice, a timeout of either run or an rc of 3
   * drops the seed, and two runs that differ reject the range.
   *
   * @throws IllegalArgumentException naming the seed whose two runs differ
   */
  static Family generate(long from, long to, Reference reference) throws IOException, InterruptedException {
    Map<Long, String> dropped = new TreeMap<>();
    List<Recording> out = new ArrayList<>();
    for (long seed = from; seed < to; seed++) {
      String where = ValuesV1.NAME + ".cases";
      CaseFormat.Block block = CaseFormat.parse(where, ValuesV1.FAMILY, ValuesV1.generate(seed).block().getBytes(UTF_8)).getFirst();
      DiffCase c = DiffCase.of(where, block);
      Outcome first = reference.run(c);
      Outcome second = first == null ? null : reference.run(c);
      if (second == null) {
        dropped.put(seed, "timeout");
        continue;
      }
      if (!first.sameAs(second)) throw new IllegalArgumentException("case " + c.id() + ": the two runs of jq-ref differ; a case must be deterministic");
      if (first.rc() == 3) {
        dropped.put(seed, "rc3");
        continue;
      }
      out.add(new Recording(c.id(), c.def(), first));
    }
    return new Family(ValuesV1.file(from, to, dropped), out);
  }

  private static List<Recording> record(Path cases) throws IOException, InterruptedException {
    List<Recording> out = new ArrayList<>();
    for (DiffCase c : DiffCase.read(cases)) {
      Outcome first = runReference(c);
      Outcome second = first == null ? null : runReference(c);
      if (second == null) throw new IllegalArgumentException("case " + c.id() + ": jq-ref ran over " + TIMEOUT_SECONDS + " s");
      if (!first.sameAs(second)) {
        throw new IllegalArgumentException(cases + ": case " + c.id() + ": the two runs of jq-ref differ; a case must be deterministic");
      }
      out.add(new Recording(c.id(), c.def(), first));
    }
    return out;
  }

  /** jq-ref's outcome of one case, or null when it ran over {@value #TIMEOUT_SECONDS} s. */
  private static Outcome runReference(DiffCase c) throws IOException, InterruptedException {
    Path scratch = Path.of(System.getProperty("java.io.tmpdir"));
    // stdin and the two output files live beside the case's directory, not in it: jq-ref sees only the case's files.
    try (CaseDirectory dir = CaseDirectory.of(c.files(), scratch);
        CaseDirectory io = CaseDirectory.of(Map.of("stdin", c.stdin()), scratch)) {
      Path in = io.path().resolve("stdin");
      Path out = io.path().resolve("stdout");
      Path err = io.path().resolve("stderr");
      List<String> command = new ArrayList<>();
      command.add(JQ_REF.toString());
      command.addAll(c.args());
      ProcessBuilder pb =
          new ProcessBuilder(command).directory(dir.path().toFile()).redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
      pb.environment().clear();
      pb.environment().putAll(c.environment(dir.path()));
      Process p = pb.start();
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly().waitFor();
        return null;
      }
      return new Outcome(p.exitValue(), Files.readAllBytes(out), Files.readAllBytes(err));
    }
  }

  /** Prints the differing lines of a recording against a fresh one; true if any byte differs. */
  private static boolean differs(Path rec, byte[] fresh) throws IOException {
    byte[] old = Files.exists(rec) ? Files.readAllBytes(rec) : new byte[0];
    if (Arrays.equals(old, fresh)) return false;
    System.err.println(rec + ": differs from a fresh recording (edited by hand, a case changed, or jq-ref changed):");
    String[] was = new String(old, UTF_8).split("\n", -1);
    String[] now = new String(fresh, UTF_8).split("\n", -1);
    int shown = 0;
    for (int i = 0; i < Math.max(was.length, now.length) && shown < 10; i++) {
      String a = i < was.length ? was[i] : "(no line)";
      String b = i < now.length ? now[i] : "(no line)";
      if (a.equals(b)) continue;
      System.err.println("  line " + (i + 1) + "  recorded: " + a);
      System.err.println("  line " + (i + 1) + "  jq-ref:   " + b);
      shown++;
    }
    return true;
  }
}
