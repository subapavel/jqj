package io.github.subapavel.jqj.query.ir;

/** A node's text in the filter: the char offsets {@code [start, end)}. */
public record Source(int start, int end) {}
