package io.github.subapavel.jqj.query.run;

/** Every error a filter raises (ADR-0008): it carries the error value and is created without a stack trace. */
public final class FilterException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  // A value need not be Serializable: serializing the exception fails instead of dropping it, as a transient would.
  @SuppressWarnings("serial")
  private final Object value;

  public FilterException(Object value) {
    super(null, null, false, false);
    this.value = value;
  }

  /** The error value. */
  public Object value() {
    return value;
  }
}
