package io.github.subapavel.jqj;

import io.github.subapavel.jqj.host.Host;
import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.query.compile.Compiler;
import java.util.Objects;

/**
 * Compiles filters (ADR-0016). Immutable and thread-safe; it keeps no filter. It carries its limits and
 * host; nothing reads them before spec 2.
 */
public final class JqEngine {
  private final Limits limits;
  private final Host host;

  private JqEngine(Limits limits, Host host) {
    this.limits = limits;
    this.host = host;
  }

  /** A builder with {@link Limits#embedded()} and {@link Host#sandboxed()}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Compiles {@code text}; only {@code .} is built, any other text is a Stub. */
  public JqFilter compile(String text) {
    return new JqFilter(Compiler.compile(Parser.parse(text)));
  }

  /** A new Workspace, for one running evaluation at a time. */
  public Workspace newWorkspace() {
    return new Workspace();
  }

  /** Builds a {@link JqEngine}. */
  public static final class Builder {
    private Limits limits = Limits.embedded();
    private Host host = Host.sandboxed();

    private Builder() {}

    /** @throws NullPointerException if {@code limits} is null */
    public Builder limits(Limits limits) {
      this.limits = Objects.requireNonNull(limits, "limits");
      return this;
    }

    /** @throws NullPointerException if {@code host} is null */
    public Builder host(Host host) {
      this.host = Objects.requireNonNull(host, "host");
      return this;
    }

    public JqEngine build() {
      return new JqEngine(limits, host);
    }
  }
}
