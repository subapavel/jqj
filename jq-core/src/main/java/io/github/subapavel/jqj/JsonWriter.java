package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.Stub;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;

/** Sinks that write each result as UTF-8 JSON (ADR-0016, ADR-0015). Both are Stubs so far. */
public final class JsonWriter {
  private JsonWriter() {}

  public static JqSink sink(Workspace workspace, WritableByteChannel out, OutputOptions options) {
    throw Stub.unbuilt("JsonWriter.sink(WritableByteChannel)");
  }

  public static JqSink sink(Workspace workspace, OutputStream out, OutputOptions options) {
    throw Stub.unbuilt("JsonWriter.sink(OutputStream)");
  }
}
