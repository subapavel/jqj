package io.github.subapavel.jqj.query.ir;

import io.github.subapavel.jqj.query.run.Fn0;

/** An operation a format adds (ADR-0005), called through an {@link Intrinsic}: its name and its one-result form. */
public record Operation(String name, Fn0 fn) {}
