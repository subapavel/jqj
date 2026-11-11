package io.github.subapavel.jqj;

/** The values of a filter's variables for one run (ADR-0016). Only the empty bindings exist so far. */
public final class Bindings {
  private Bindings() {}

  public static Builder builder() {
    return new Builder();
  }

  /** Builds {@link Bindings}. */
  public static final class Builder {
    private Builder() {}

    public Bindings build() {
      return new Bindings();
    }
  }
}
