package io.github.subapavel.jqj.query.run;

/** The call form of an operation with exactly one result (ADR-0009); {@code ws} comes with its first user. */
@FunctionalInterface
public interface Fn0 {
  /** The one result for {@code in}. */
  Object apply(Object in);
}
