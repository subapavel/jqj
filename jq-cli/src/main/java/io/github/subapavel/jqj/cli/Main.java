package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.Bindings;
import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqFilter;
import io.github.subapavel.jqj.JqFilterException;
import io.github.subapavel.jqj.JqSink;
import io.github.subapavel.jqj.JqSyntaxException;
import io.github.subapavel.jqj.JsonDoc;
import io.github.subapavel.jqj.JsonWriter;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.host.Host;
import io.github.subapavel.jqj.value.JqString;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;

/**
 * The command line (ADR-0013). Not exported API: the oracle reaches {@link #run} on the class path.
 *
 * <p>Built so far (#33): {@code -c} anywhere, the filter as the first other argument ({@code .} when there is none),
 * then at most one file, read instead of stdin. A filter error ends the run with jq's line {@code jq: error (at
 * <name>:N): <text>} and exit code 5 (#34). Without {@code -c} the output is pretty, jq's default indent 2 (#37).
 * Every other option is a Stub until #38.
 */
public final class Main {

  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String STUB = "jqj stub: ";

  /** Exit code of a usage error: an option, a second file, a file that cannot be read. */
  private static final int USAGE = 2;

  /** Exit code of a filter that does not compile. */
  private static final int COMPILE = 3;

  /** Exit code of a failed evaluation: a parse error of the input, a filter error, a Stub during run or write. */
  private static final int FAILED = 5;

  private Main() {}

  /**
   * The in-process entry (ADR-0020): runs one invocation and returns its exit code. Compiles the filter, makes the
   * sink, reads all of the file or stdin, then runs the filter once on the one document there. An input of only SP,
   * TAB, CR and LF is zero documents: exit code 0 and no output (#38's input-stream reader replaces this rule).
   *
   * @throws UncheckedIOException when stdin, stdout or stderr fails
   */
  public static int run(Invocation invocation) {
    boolean compact = false;
    String filter = null;
    String file = null;
    for (String arg : invocation.args()) {
      if (arg.equals("-c")) {
        compact = true;
      } else if (arg.startsWith("-")) {
        return report(invocation, USAGE, STUB + "the option " + arg + " is not built yet");
      } else if (filter == null) {
        filter = arg;
      } else if (file == null) {
        file = arg;
      } else {
        return report(invocation, USAGE, STUB + "a second input file is not built yet");
      }
    }

    JqEngine engine = JqEngine.builder().limits(Limits.cli()).host(Host.sandboxed()).build();
    JqFilter compiled;
    try {
      compiled = engine.compile(filter == null ? "." : filter);
    } catch (JqCompileException e) {
      return report(invocation, COMPILE, e.getMessage());
    }
    Workspace workspace = engine.newWorkspace();
    try {
      JqSink sink = JsonWriter.sink(workspace, invocation.out(), compact ? OutputOptions.compact() : OutputOptions.pretty());
      byte[] input;
      if (file == null) {
        input = invocation.in().readAllBytes();
      } else {
        try {
          input = Files.readAllBytes(invocation.cwd().resolve(file));
        } catch (IOException e) {
          return report(invocation, USAGE, STUB + "the error for an input file that cannot be read (" + e + ") is not built yet");
        }
      }
      if (!blank(input)) {
        try (JsonDoc doc = workspace.read(MemorySegment.ofArray(input))) {
          compiled.run(doc.root(), Bindings.builder().build(), sink, workspace);
        } catch (JqFilterException e) {
          // The results before the error leave first, as jq's do. Every error value built so far is a string (#34).
          invocation.out().flush();
          String name = file == null ? "<stdin>" : file;
          return report(invocation, FAILED, "jq: error (at " + name + ":" + ErrorLine.of(input) + "): " + ((JqString) e.value()).value());
        }
      }
      invocation.out().flush();
      return 0;
    } catch (JqSyntaxException e) {
      return report(invocation, FAILED, "jq: parse error: " + e.getMessage());
    } catch (JqCompileException e) {
      return report(invocation, FAILED, e.getMessage());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Whether {@code input} holds only SP, TAB, CR and LF, jq's whitespace (ADR-0015), or nothing. */
  private static boolean blank(byte[] input) {
    for (byte b : input) {
      if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
        return false;
      }
    }
    return true;
  }

  /** Writes {@code message} and an LF to stderr; returns {@code rc}. */
  private static int report(Invocation invocation, int rc, String message) {
    try {
      invocation.err().write((message + "\n").getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rc;
  }
}
