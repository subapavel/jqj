package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.value.JqValue;

/** A compiled filter, immutable and thread-safe (ADR-0016). Running is a Stub so far. */
public final class JqFilter {
  JqFilter() {}

  /** Runs the filter on {@code input}; true when the results ran out, false when the sink stopped. */
  public boolean run(JqValue input, Bindings bindings, JqSink sink, Workspace workspace) {
    throw Stub.unbuilt("JqFilter.run");
  }
}
