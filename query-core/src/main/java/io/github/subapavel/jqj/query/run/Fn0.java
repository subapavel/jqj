package io.github.subapavel.jqj.query.run;

/** The call form of an operation with exactly one result (ADR-0009). */
@FunctionalInterface
public interface Fn0 {
  /** The one result for {@code in} in the run {@code state} belongs to, with the run state first (ADR-0030). */
  Object apply(RunState state, Object in);
}
