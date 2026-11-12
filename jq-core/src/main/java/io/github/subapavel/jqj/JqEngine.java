package io.github.subapavel.jqj;

import io.github.subapavel.jqj.host.Host;
import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.internal.parse.Resolver;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import java.util.Objects;

/**
 * Compiles filters (ADR-0016). Immutable and thread-safe; it keeps no filter. It carries its limits, which every run
 * of its filters takes as its budgets (ADR-0030), its host, which nothing reads before spec 2, and its compile mode.
 */
public final class JqEngine {
  private final Limits limits;
  private final Host host;
  private final CompileMode compileMode;

  private JqEngine(Limits limits, Host host, CompileMode compileMode) {
    this.limits = limits;
    this.host = host;
    this.compileMode = compileMode;
  }

  /** A builder with {@link Limits#embedded()} and {@link Host#sandboxed()}. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Compiles {@code text}: parses jq's whole grammar, then resolves it (ADR-0032). Resolved so far: {@code .}, fields,
   * {@code .[k]}, {@code .[a:b]} and {@code .[]} with their {@code ?}, {@code |}, {@code ,}, parentheses, literals and
   * bare {@code @text}; any other construct is a Stub.
   *
   * @throws JqCompileException with jq's error blocks for a lexical, syntax or recovery error (ADR-0031), jq's
   *     not-given error for a program without a top-level filter, or a Stub's
   */
  public JqFilter compile(String text) {
    return new JqFilter(Compiler.compile(Resolver.resolve(Parser.parse(text)), compileMode), limits);
  }

  /** A new Workspace, for one running evaluation at a time. */
  public Workspace newWorkspace() {
    return new Workspace();
  }

  /** The compile mode of this engine's filters; engine-internal, for the compile-mode oracle (ADR-0018, #60). */
  CompileMode compileMode() {
    return compileMode;
  }

  /** Builds a {@link JqEngine}. */
  public static final class Builder {
    private Limits limits = Limits.embedded();
    private Host host = Host.sandboxed();
    private CompileMode compileMode = CompileMode.OPTIMISED;

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

    /**
     * Sets the compile mode, {@link CompileMode#OPTIMISED} by default. Engine-internal: the compile-mode oracle reaches
     * it by reflection (ADR-0018, #60).
     *
     * @throws NullPointerException if {@code compileMode} is null
     */
    Builder compileMode(CompileMode compileMode) {
      this.compileMode = Objects.requireNonNull(compileMode, "compileMode");
      return this;
    }

    public JqEngine build() {
      return new JqEngine(limits, host, compileMode);
    }
  }
}
