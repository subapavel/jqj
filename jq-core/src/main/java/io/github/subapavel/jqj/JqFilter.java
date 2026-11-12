package io.github.subapavel.jqj;

import io.github.subapavel.jqj.query.compile.Executable;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.value.JqValue;

/** A compiled filter, immutable and thread-safe (ADR-0016). It runs in push form (ADR-0008). */
public final class JqFilter {
  private final Executable executable;

  JqFilter(Executable executable) {
    this.executable = executable;
  }

  /**
   * Runs the filter on {@code input}; true when the results ran out, false when the sink stopped.
   *
   * @throws JqFilterException with jq's error value when the filter raises an error
   */
  public boolean run(JqValue input, Bindings bindings, JqSink sink, Workspace workspace) {
    try {
      // query-core hands values over as Object; every value this filter pushes or raises is a jq value.
      return executable.run(input, v -> sink.emit((JqValue) v));
    } catch (FilterException e) {
      throw new JqFilterException((JqValue) e.value());
    }
  }
}
