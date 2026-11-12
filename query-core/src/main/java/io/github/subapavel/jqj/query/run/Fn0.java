package io.github.subapavel.jqj.query.run;

/** The call form of an operation with exactly one result (ADR-0009). */
@FunctionalInterface
public interface Fn0 {
  /** The one result for {@code in}. */
  Object apply(Object in);

  /**
   * The one result for {@code in} in the run {@code state} belongs to: the form a backend calls, with the run state
   * first (ADR-0030). It is {@link #apply(Object)} until an operation charges its run (expand-contract).
   */
  default Object apply(RunState state, Object in) {
    return apply(in);
  }
}
