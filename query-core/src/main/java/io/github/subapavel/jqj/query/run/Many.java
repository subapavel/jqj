package io.github.subapavel.jqj.query.run;

/**
 * The call form of an operation with any number of results (ADR-0009, ADR-0034): a resumable cursor, so the caller
 * runs it as a loop and charges one step per result before it emits it. A cursor is a {@code long} the operation
 * chooses, {@link #END} after the last result. The arguments come as an array, as many as the call has; the operation
 * never writes it.
 */
public non-sealed interface Many extends CallForm {
  /** The cursor after the last result. */
  long END = -1;

  /** The cursor of the first result for {@code in} and {@code args}, or {@link #END} when there is none. */
  long first(RunState state, Object in, Object[] args);

  /** The cursor of the result after {@code cursor}'s, or {@link #END}. */
  long next(RunState state, Object in, Object[] args, long cursor);

  /** The result at {@code cursor}, which is not {@link #END}. */
  Object value(RunState state, Object in, long cursor);
}
