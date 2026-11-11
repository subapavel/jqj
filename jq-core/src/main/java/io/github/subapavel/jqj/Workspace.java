package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.json.JsonReader;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.internal.json.TapeLimits;
import java.lang.foreign.MemorySegment;

/**
 * The reusable state of one running evaluation at a time (ADR-0016): so far the reader and its reused tape, the
 * writer's print buffer and the step counter.
 */
public final class Workspace {
  private final JsonReader reader = new JsonReader(TapeLimits.ADR_0002);

  /** The writer's printer and its 64 KiB buffer, shared by every sink over this Workspace. */
  final Printer printer = new Printer();

  /** Steps charged so far (ADR-0008, F-23); nothing enforces them until spec 2 wires {@code Limits.steps}. */
  long steps;

  Workspace() {}

  /**
   * Reads one document onto this Workspace's tape (ADR-0015), charging one step per started KiB. The previous
   * document of this Workspace goes stale as the read starts, also when the read then fails (ADR-0026).
   *
   * @throws JqSyntaxException when the bytes are not exactly one JSON document
   */
  public JsonDoc read(MemorySegment json) {
    JsonDoc doc = new JsonDoc(reader.read(json));
    // The read passed the document limit, 2^31-3 bytes, so the KiB fit an int.
    tick((int) ((json.byteSize() + 1023) >>> 10));
    return doc;
  }

  /** Charges {@code steps} steps at once (ADR-0008 as amended by ADR-0015: the reader and the writer, per KiB). */
  void tick(int steps) {
    this.steps += steps;
  }
}
