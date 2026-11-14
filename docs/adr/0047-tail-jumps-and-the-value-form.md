# Tail calls are a pending jump in the run state; the value form runs only in the optimised compile; the tail emitter stays

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 2b round 1); amends ADR-0011 (tail calls in the push interpreter) and ADR-0043 (closes "may subsume"); its "at most one" is F-56's

ADR-0011 makes tail calls jumps and gives the bytecode backend's loops. In the push interpreter an op
runs inside its producer's emit, so a call cannot loop where it stands: the activation must be left
first. ADR-0008's value form arrives in 2b beside ADR-0043's tail emitter, which the value form "may
subsume".

## Decision

- **A pending jump.** The run state gains a pending-jump field: the callee `fn`, the input and the
  value arguments. A tail-position op sets it and returns at once. The function's entry loop finds
  it and re-enters at `fn`, so neither the Java stack nor the recursion-depth count grows
  (ADR-0046). `TAIL_SELF` and `TAIL_GROUP` both use it. A call is a tail call only under ADR-0006's
  four conditions, with each left context at most one in F-56's sense (M1). A jump ticks one step,
  as ADR-0008 charges every tail jump. The field joins `RunState` with its first user (ADR-0030).
- **The value form only in OPTIMISED.** A node runs `eval` (ADR-0008) where its cardinality is at
  most one (F-56) and may-break-out is false. CONSERVATIVE never calls `eval` and keeps the Holder
  path, so the compile-mode oracle stays an independent second opinion (ADR-0018).
- **The tail emitter stays** in both modes as the kind-only floor (ADR-0043). It reads no fact, so
  it keeps wide constructs flat where the analyses prove less, and everywhere in CONSERVATIVE.
  ADR-0043's "may subsume" is closed: the value form adds to the tail emitter and replaces nothing.
- **Required**: F-56's three jq-ref cases are differential cases of the value-form and tail-call
  tickets. ADR-0046's `0|until(.>=10000000; .+1)` → `10000000` (jq-ref, 3.4 s) is the witness that
  a jump replaces its activation.

## Considered options

- **A thrown tail signal**: against founding §8, as ADR-0008 found for break and stop.
- **A jump sentinel returned by `run`**: one compare on every return in hot code, as ADR-0008 found
  for a BREAK sentinel. The field is read only by a function's entry loop.
- **The value form in both modes**: CONSERVATIVE would run the optimised compile's paths, and the
  compile-mode oracle would stop being a second opinion.
- **The value form subsuming the tail emitter**: CONSERVATIVE has no facts, so its wide objects,
  interpolations and operator runs would nest per part again (ADR-0043).
