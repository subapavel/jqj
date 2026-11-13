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
- (#36) Read a verify worktree's PIT `mutations.xml` only after the gate has finished; mid-run it can be empty.
- (#35) An empty @TestFactory map passes vacuously: a FAIL (CODING_STANDARDS Tests §1). jq's GitHub source is unreachable via gh here; translation checks rest on jq-ref recordings.
- (#61) A merge of the integration tip can bring in an ADR amendment newer than the approved design: read the ADR status lines on the tip, not the design comment.
- (#61) An op that calls the static `Printer.json` charges no ticks: check every new op that walks a value for `apply(RunState, …)`.
- (#61) A tick test whose budget sits far below the charge only proves something is charged; pin the rate with a pair (N completes, N−1 ends).
- (#62) Syntax-error stderr is not compared by the oracle, so token-name tables and lookahead sets are unit-test-only: count the witnessed rows.
- (#62) A hand-written parser over parser.y counts as a translation for NOTICE (Lexer.java precedent); error-recovery logic must name its source (Bison manual vs parser.c vs GPL yacc.c).
- (#62) F-13 is the JSON reader's depth limit; it does not cover the filter parser's recursion.
- (#63) The oracle runs without budgets, so every new `state.tick` needs its own adjacent budget pair: grep the diff for `tick(` and match each hit to a test.
- (#63) A `case JqValue other` total pattern counts as a default on a sealed type. `ObjectView.isFirst`/`lastValueOf`-style scans inside a walk are loops over data and need ticks (F-40).
- (#63) Sign-encoded or long-packed scan returns are this codebase's recurring hot-path idiom: raise once as a carry.
- (#64) A file headed "Translated from jq" must appear in NOTICE: diff NOTICE against `git grep -l "Translated from jq"`. A measured constant needs one absolute pin; one pair per rate cannot catch `*`→`/`, ask for two counts.
- (#64) DiffRunner skips stderr only for `jq: error: syntax error`; action-only compile errors are compared byte for byte.

- (#65) A constant-operand row (`1 + 2`) in a run-budget test is folded at parse time: check every filter in a budget test for a fold. An ADR rate list covers only the loops it names: grep each new loop over Members for a tick and match it to a rate. Diff NOTICE against `git grep -l "Translated from jq"` on every ticket.

- (#65) A budget started per operator inside a recursive fold does not bound the fold: a container of folds adds them up; check where each fold RunState begins. A time or out-of-memory test witnesses only that a bound exists; the bound value needs a pair at the bound. A shared helper with a flag that skips a tick for one caller: check that caller against ADR-0008.

- (#65) A shape check that repeats what a fold does can be hidden by the fold fallback: pick a case where only the shape decides. A parse-time fold has no step budget unless one is given: check compile-time arithmetic for O(n*m) work, and that each compile shares one fold budget.
