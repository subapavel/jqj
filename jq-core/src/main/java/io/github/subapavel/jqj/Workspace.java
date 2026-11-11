package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.json.JsonReader;
import io.github.subapavel.jqj.internal.json.TapeLimits;
import java.lang.foreign.MemorySegment;

/** The reusable state of one running evaluation at a time (ADR-0016): so far the reader and its reused tape. */
public final class Workspace {
  private final JsonReader reader = new JsonReader(TapeLimits.ADR_0002);

  Workspace() {}

  /**
   * Reads one document onto this Workspace's tape (ADR-0015). The previous document of this Workspace goes stale
   * as the read starts, also when the read then fails (ADR-0026).
   *
   * @throws JqSyntaxException when the bytes are not exactly one JSON document
   */
  public JsonDoc read(MemorySegment json) {
    return new JsonDoc(reader.read(json));
  }
}
