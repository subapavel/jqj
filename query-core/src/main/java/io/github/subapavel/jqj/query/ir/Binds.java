package io.github.subapavel.jqj.query.ir;

import java.util.List;

/**
 * {@code Bind(value1, slot1, … Bind(valueN, slotN, body))} as one node, n ≥ 2, the first outermost (ADR-0043).
 *
 * <p>Scaffold of the #92 test-author: the record ADR-0043 states, so the red tests compile; the implementer owns it.
 */
public record Binds(Source source, List<Node> values, List<Integer> slots, Node body) implements Node {}
