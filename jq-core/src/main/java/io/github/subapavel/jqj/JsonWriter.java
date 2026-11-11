package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.json.Printer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * Sinks that write each result as UTF-8 JSON (ADR-0016, ADR-0015, ADR-0027): one line per result, drained into the
 * target before emit returns, never flushed. Compact only so far; {@code pretty()} is a Stub until #39.
 */
public final class JsonWriter {
  private JsonWriter() {}

  /** As {@link #sink(Workspace, OutputStream, OutputOptions)}, over {@code Channels.newOutputStream(out)}. */
  public static JqSink sink(Workspace workspace, WritableByteChannel out, OutputOptions options) {
    return sink(workspace, Channels.newOutputStream(out), options);
  }

  /**
   * A sink that prints each result and one LF into {@code out}, charging one step per started KiB of each drain
   * (F-23). A target's IOException leaves emit as an UncheckedIOException with it as the cause (ADR-0027).
   */
  public static JqSink sink(Workspace workspace, OutputStream out, OutputOptions options) {
    if (options != OutputOptions.compact()) {
      throw Stub.unbuilt("pretty output");
    }
    Printer printer = workspace.printer;
    Printer.Target target =
        (buffer, length) -> {
          try {
            out.write(buffer, 0, length);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          workspace.tick((length + 1023) >>> 10);
        };
    return v -> {
      printer.line(v, target);
      return true;
    };
  }
}
