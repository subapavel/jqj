# reviewer-standards memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- Two-sided guards (`a.isEmpty() != b.isEmpty()`): PIT only negates the whole condition, so one
  half can go unwitnessed with a green mutation score. Check each half has a test case.
- "Equivalent mutant" claims: check them against the public seam, not the current implementation.
- Config-only guards and partial `&&` conditions in hand-written validators often ship with one
  case of several witnessed. Ask for one case per line.
- A seeded loop inside one `@Test` recurs: the standard is one parameterised case per seed, the
  seed in the name.
- A rule moved into one helper and claimed by its new test still needs a row per clause.
- Stale text after an ADR amendment: grep the code comments for the old wording.
- A fake that echoes what it was asked cannot witness a guarantee about that value: check port
  fakes against the ADR wording.
- Mutable accumulators carrying boolean "kind" flags: watch for a sealed type instead.
- Enforcer bans that one module lifts use `<phase>none</phase>` on the execution id; any child
  POM can lift one the same way, so grep diffs for those ids.
- `gh issue view` fails (GraphQL blocked); use `gh api repos/<owner>/<repo>/issues/N` and
  `/comments`. The approved design is in the ticket comments, and it can sanction choices that
  look like standards deviations.
- (#33) Before prescribing a position assert, check it is jq-ref's (grilling item 7); a channel target has no flush, so "never flushed" on it is vacuous.
- (#34) Check each expected-value row against the `.rec`, not the comment; an oracle def is sha256(case..end lines + LF)[:12].
