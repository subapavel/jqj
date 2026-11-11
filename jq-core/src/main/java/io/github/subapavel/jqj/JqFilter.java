package io.github.subapavel.jqj;

import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.value.JqValue;

/** A compiled filter, immutable and thread-safe (ADR-0016). It runs in push form (ADR-0008). */
public final class JqFilter {
  private final Executable executable;

  JqFilter(Executable executable) {
    this.executable = executable;
  }

  /** Runs the filter on {@code input}; true when the results ran out, false when the sink stopped. */
  public boolean run(JqValue input, Bindings bindings, JqSink sink, Workspace workspace) {
    // query-core hands values over as Object; every value this filter pushes is a jq value.
    return executable.run(input, v -> sink.emit((JqValue) v));
  }
}
