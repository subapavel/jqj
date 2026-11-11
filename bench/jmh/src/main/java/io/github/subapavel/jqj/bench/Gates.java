package io.github.subapavel.jqj.bench;

import io.github.subapavel.jqj.Bindings;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqFilter;
import io.github.subapavel.jqj.JsonDoc;
import io.github.subapavel.jqj.JsonWriter;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.bench.Inputs.Size;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The gate scenarios G1, G6 and G7 of ADR-0019 under the bench ids of ADR-0024. A method's name is its bench id with
 * each {@code .} written {@code _}, because a Java name cannot hold a dot ({@code G1_10m} is {@code G1.10m}).
 *
 * <p>The class carries ADR-0019's JMH settings ({@code -bm avgt -f 2 -wi 3 -w 1s -i 5 -r 1s}, a 2 GiB fixed heap, one
 * thread), so {@code java -jar benchmarks.jar -prof gc -rf json} runs them as the ADR says. G7 overrides them with
 * single-shot mode, 10 forks and no warm-up. A setting given on the command line overrides the annotation for every
 * benchmark, G7 included.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(
    value = 2,
    jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
public class Gates {

  @Benchmark
  public long G1_1k(G1At1k s) {
    return s.operation();
  }

  @Benchmark
  public long G1_100k(G1At100k s) {
    return s.operation();
  }

  @Benchmark
  public long G1_10m(G1At10m s) {
    return s.operation();
  }

  @Benchmark
  public long G6_compact_10m(G6CompactAt10m s) {
    return s.operation();
  }

  @Benchmark
  public long G6_pretty_10m(G6PrettyAt10m s) {
    return s.operation();
  }

  /** Cold start in the JVM: build the engine, compile {@code .a}, run it on the 1 KB document. */
  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Fork(
      value = 10,
      jvmArgs = {"-Xms2g", "-Xmx2g"})
  @Warmup(iterations = 0)
  @Measurement(iterations = 1, batchSize = 1)
  public long G7_engine_1k(Cold s) {
    JqEngine engine = JqEngine.builder().limits(Limits.embedded()).build();
    JqFilter filter = engine.compile(".a");
    return once(filter, engine.newWorkspace(), s.input, OutputOptions.compact(), s.out);
  }

  /**
   * Cold start as a process: {@code jqj -c .a} on the 1 KB document, through the launcher that {@code jq-cli}'s
   * {@code package} writes. The system property {@code jqj.launcher} names it (default {@code jq-cli/target/jqj},
   * relative to the directory JMH runs in).
   *
   * @throws IllegalStateException if the process exits with a code other than 0
   */
  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Fork(
      value = 10,
      jvmArgs = {"-Xms2g", "-Xmx2g"})
  @Warmup(iterations = 0)
  @Measurement(iterations = 1, batchSize = 1)
  public int G7_process_1k(ColdFile s) throws IOException, InterruptedException {
    Process jqj =
        new ProcessBuilder(s.launcher, "-c", ".a", s.file.toString())
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.INHERIT)
            .start();
    int code = jqj.waitFor();
    if (code != 0) {
      throw new IllegalStateException(s.launcher + " exited with " + code);
    }
    return code;
  }

  /**
   * One operation of ADR-0019: read the document onto the Workspace, run the filter, write each result through
   * {@link JsonWriter#sink} into {@code out}. Returns the bytes written so far, which JMH consumes.
   */
  static long once(JqFilter filter, Workspace workspace, MemorySegment input, OutputOptions options, Discard out) {
    try (JsonDoc doc = workspace.read(input)) {
      filter.run(doc.root(), Bindings.builder().build(), JsonWriter.sink(workspace, out, options), workspace);
    }
    return out.written;
  }

  /** A scenario's state: the filter, compiled once, and the reused Workspace, warmed by one operation in setup. */
  public abstract static class Scenario {
    private final Discard out = new Discard();
    private JqFilter filter;
    private Workspace workspace;
    private MemorySegment input;
    private OutputOptions options;

    final void prepare(String text, Size size, OutputOptions options) {
      JqEngine engine = JqEngine.builder().limits(Limits.embedded()).build();
      this.filter = engine.compile(text);
      this.workspace = engine.newWorkspace();
      this.input = MemorySegment.ofArray(Inputs.orders(size));
      this.options = options;
      operation();
    }

    final long operation() {
      return once(filter, workspace, input, options, out);
    }
  }

  /** G1: {@code .a.b.c} on orders 1k. */
  @State(Scope.Thread)
  public static class G1At1k extends Scenario {
    @Setup
    public void setUp() {
      prepare(".a.b.c", Size.K1, OutputOptions.compact());
    }
  }

  /** G1: {@code .a.b.c} on orders 100k. */
  @State(Scope.Thread)
  public static class G1At100k extends Scenario {
    @Setup
    public void setUp() {
      prepare(".a.b.c", Size.K100, OutputOptions.compact());
    }
  }

  /** G1: {@code .a.b.c} on orders 10m. */
  @State(Scope.Thread)
  public static class G1At10m extends Scenario {
    @Setup
    public void setUp() {
      prepare(".a.b.c", Size.M10, OutputOptions.compact());
    }
  }

  /** G6: {@code -c .} on orders 10m. */
  @State(Scope.Thread)
  public static class G6CompactAt10m extends Scenario {
    @Setup
    public void setUp() {
      prepare(".", Size.M10, OutputOptions.compact());
    }
  }

  /** G6: pretty {@code .} on orders 10m. */
  @State(Scope.Thread)
  public static class G6PrettyAt10m extends Scenario {
    @Setup
    public void setUp() {
      prepare(".", Size.M10, OutputOptions.pretty());
    }
  }

  /** G7 in the JVM: only the input exists before the shot. */
  @State(Scope.Thread)
  public static class Cold {
    final Discard out = new Discard();
    MemorySegment input;

    @Setup
    public void setUp() {
      input = MemorySegment.ofArray(Inputs.orders(Size.K1));
    }
  }

  /** G7 as a process: the 1 KB document in a temporary file. */
  @State(Scope.Thread)
  public static class ColdFile {
    final String launcher = System.getProperty("jqj.launcher", "jq-cli/target/jqj");
    Path file;

    @Setup
    public void setUp() throws IOException {
      file = Files.createTempFile("jqj-orders-1k-", ".json");
      Files.write(file, Inputs.orders(Size.K1));
    }

    @TearDown
    public void tearDown() throws IOException {
      Files.deleteIfExists(file);
    }
  }

  /** A channel that counts the bytes written to it and keeps none. */
  static final class Discard implements WritableByteChannel {
    long written;

    @Override
    public int write(ByteBuffer src) {
      int n = src.remaining();
      src.position(src.limit());
      written += n;
      return n;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }
}
