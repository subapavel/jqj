package io.github.subapavel.jqj.query.ir;

/** A call of an {@link Operation} on the input: its one result. Arguments come with their first user. */
public record Intrinsic(Source source, Operation op) implements Node {}
