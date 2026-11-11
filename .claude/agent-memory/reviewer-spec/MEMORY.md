# reviewer-spec memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- A ticket's scope can contradict the spec's Implementation Decisions: compare them.
- A case built with a stand-in for a function that did not exist yet keeps it after that
  function lands: check old cases when a function arrives.
- A general "accepted" clause in an ADR can hide exceptions the code keeps: grep the code for them.
- An implementer's "decision" can swap a component the spec names for a reason the spec never
  imposed; check it against the spec and the licence list.
- (#34) Probe a design's Stub token list with the token followed by a valid one (`..a`), not alone.
