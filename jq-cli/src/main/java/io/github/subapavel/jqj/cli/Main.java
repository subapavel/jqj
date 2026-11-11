package io.github.subapavel.jqj.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;

/** The command line (ADR-0013). Not exported API: the oracle reaches {@link #run} on the class path. */
public final class Main {

  /** The Stub of the whole command line (ADR-0024) until the command-line ticket builds it. */
  private static final byte[] STUB = "jqj stub: the command line is not built yet\n".getBytes(UTF_8);

  private Main() {}

  /**
   * The in-process entry (ADR-0020): runs one invocation and returns its exit code. A stub shell for now: every
   * invocation ends with a Stub error on stderr and exit code 2, whatever its arguments.
   *
   * @throws UncheckedIOException when stderr cannot be written
   */
  public static int run(Invocation invocation) {
    try {
      invocation.err().write(STUB);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return 2;
  }
}
