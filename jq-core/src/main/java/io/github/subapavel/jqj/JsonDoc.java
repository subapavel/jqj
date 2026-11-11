package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqValue;

/** A document that was read (ADR-0016). Both members are Stubs so far. */
public final class JsonDoc implements AutoCloseable {
  JsonDoc() {}

  /** The document's root value, the run input. */
  public JqValue root() {
    throw Stub.unbuilt("JsonDoc.root");
  }

  @Override
  public void close() {
    throw Stub.unbuilt("JsonDoc.close");
  }
}
