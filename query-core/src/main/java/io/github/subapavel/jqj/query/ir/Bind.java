package io.github.subapavel.jqj.query.ir;

/**
 * {@code value as $slot | body} (ADR-0034, GLOSSARY "Slot"): each result of {@code value} sets the slot, and {@code body}
 * runs on the Bind's own input. A tree has one Bind per slot, and a {@link Var} of the slot occurs only inside
 * {@code body}.
 */
public record Bind(Source source, Node value, int slot, Node body) implements Node {}
