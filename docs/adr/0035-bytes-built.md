# Bytes built: charged by the builder, before allocation, in RunState

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #64; designer draft); amends ADR-0030 (bytes built join RunState) and ADR-0017 (how the fixed charges are measured, F-39)

#64 is the first ticket that builds arrays, objects and strings during a run, so it is the first user of ADR-0017's bytes-built budget. ADR-0030 deferred the counter to that user. This ADR fixes where the charge happens and how its fixed costs are set.

## Decision

- **RunState** holds the bytes-built count and its limit. `chargeBuilt(n)` adds `n` and, past the limit, raises `Termination(BUDGET, Budget.BYTES_BUILT)`; `JqFilter.run` translates it to exit 5 with the message "Bytes-built budget exceeded" (ours, as ADR-0030's other budget messages are). `end` leaves it unlimited, as for every budget.
- **The builder charges before it allocates**: a collector, an object constructor or a string builder calls `chargeBuilt` for what it is about to add, so a run never holds more than its limit plus one charge.
- **`Charges`** (in `query-core`) names the rates: `ELEMENT` 8 B per array element, `ENTRY` 16 B per object entry (ADR-0017), and `CONTAINER` and `STRING`, the fixed charges per built container and per built string. The ticket measures `CONTAINER` and `STRING` with JMH `-prof gc` (allocated bytes per operation) and records them in `bench/RESULTS.md`.
- **A collector charges per element collected**, not by the capacity it grows to; growth beyond the elements is the builder's own cost (amends ADR-0017's "growth charges the capacity it adds" for collectors only; copies and `detach()` keep ADR-0017's rule).
- **Ticks**: a collector's growth copies charge no tick; they average a constant per element and are bounded by the body's own ticks. A slice copy keeps ADR-0034's tick per element and also charges `CONTAINER + ELEMENT × length` before it allocates.
- Number boxes are never charged (ADR-0017).

## Considered options

- **Charge after building**: a single large construction overshoots the limit by its own size.
- **Charge by capacity in collectors**: the count then depends on the growth policy, so a change to it moves budget results.
- **JOL to measure the fixed charges**: GPL-2.0, not on the licence list (F-39).
- **A counter in the Workspace**: `query-core` cannot name it (founding §14), and the other budgets are already in RunState.
