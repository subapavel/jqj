package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.Stub;
import java.lang.foreign.MemorySegment;

/** The reusable state of one running evaluation at a time (ADR-0016). Reading is a Stub so far. */
public final class Workspace {
  Workspace() {}

  /** Reads a document onto this Workspace's tape. */
  public JsonDoc read(MemorySegment json) {
    throw Stub.unbuilt("Workspace.read");
  }
}
