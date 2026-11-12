package io.github.subapavel.jqj.query.ir;

/** The value its {@link Bind} set in {@code slot} (ADR-0034): one result, whatever the input. */
public record Var(Source source, int slot) implements Node {}
