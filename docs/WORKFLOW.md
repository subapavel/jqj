# Workflow

How work goes from an idea to `main`. Taken from flottila-mapping on 2026-11-10 (ADR-0001), where
it has carried several specs to `main`. It keeps Flottila's rigour (architect who never codes, red
first, evidence words, findings, class-based model choice, gate reviewer) on Matt Pocock's skill
chain (wayfinder, grilling, specs, tracer-bullet tickets, parallel implementers on an integration
branch, two-axis review). Skills are in `.claude/skills/`; the upstream ones are unchanged
(`.claude/skills/UPSTREAM.md`).

## 1. The shape

```
fog ──/wayfinder──▶ decisions (ADRs, GLOSSARY) ──/grill-with-docs──▶ /to-spec ──▶ /to-tickets
                                                                                    │
      main ◀── PR (/pr) ◀── /code-review on the integration branch ◀── /run-spec ◀──┘
                                                                         │
                                       3 implementers in worktrees ──▶ merger ──▶ spec/<n>-<slug>
```

| Stage | Skill | Who | Output |
| --- | --- | --- | --- |
| Chart a big unknown | `/wayfinder` | owner + architect | `wayfinder:map` issue with decision tickets |
| Resolve a decision | `/wayfinder` (work mode), `/grilling`, `/research`, `/prototype` | owner + architect, research AFK | resolution comment, ADR, glossary terms |
| Shape a deliverable | `/grill-with-docs` | owner + architect | shared understanding, glossary, ADRs |
| Write it down | `/to-spec` | architect | `spec` issue with seams and testing decisions |
| Slice it | `/to-tickets` | architect, owner approves | child tickets with blocking edges, `class:*`, `oracle:*` |
| Build it | `/run-spec` | architect + agents, autonomous | integration branch with every ticket merged |
| Review it | `/code-review` (inside `/run-spec`) | reviewer-standards ∥ reviewer-spec | findings fixed by one implementer |
| Ship it | `/pr` (inside `/run-spec`) | architect | PR to `main`, CI green, merged |
| Milestone | `/gate` | gate-reviewer | `gate: MET` or the missing conditions |
| Improve the factory | `/retro`, `/improve-codebase-architecture` | owner + architect | changes to skills, standards, checks |

## 2. Phase 0 — charting before code

Nothing is built until the decisions every ticket stands on are made: the value model and tape
layout, the IR and its analyses, the execution protocol, ownership for in-place updates, number
semantics, the JSON reader and writer, which backend comes first, the public API and sandbox, the
module layout and build, the oracles and the ratchet. Two of them need a measurement first (the
spikes of founding architecture §13: deep recursion, tape cost for tiny reads). They are charted
as one wayfinder map whose draft is `docs/wayfinder/phase-0-map.md`; `/wayfinder` publishes it to
GitHub and works it ticket by ticket. A grilling ticket needs the owner (HITL); research tickets
and the two spikes run AFK in parallel, and the owner reads their result.

Phase 0 ends when the map has no open ticket and its destination holds: ADRs for each decision,
the glossary sharpened, `docs/GATE.md` final, and the first spec ready for `/to-tickets`.

## 3. A spec

1. `/grill-with-docs` with the owner until the frontier of questions is empty.
2. `/to-spec` publishes the spec issue. Its **Testing Decisions** name the seams; these are the
   pre-agreed seams `/tdd` requires, so implementers never stop to ask for them.
3. `/to-tickets` drafts tracer-bullet tickets. Each gets blocking edges, exactly one `class:*`
   label and, where an oracle decides acceptance, an `oracle:*` label with its case ids. The owner
   approves the breakdown; then the tickets are published as sub-issues of the spec.
4. Wide refactors follow expand–contract (`/to-tickets`), never one big ticket.

## 4. Building a spec — `/run-spec`

The architect runs `.claude/skills/run-spec/SKILL.md`. In short, per ticket on the frontier:

1. claim it (assign), cut a worktree branch `t/<ticket>-<slug>` from the integration branch;
2. class C: designer draft → architect approves → test-author writes the red → architect reads
   the test files and approves; A/B: the implementer runs `/tdd` at the spec's seams; oracle
   tickets: the named cases are the red;
3. implementer makes it green in its worktree, runs the ticket's scoped checks, merges the
   integration tip into its branch, hands back;
4. verifier runs the **ticket gate** in that worktree (§6);
5. class C only: `/code-review` on the ticket diff (both axes, xhigh) — FAIL goes back to the
   same implementer; a second FAIL means the ticket was wrong: the designer rewrites it;
6. merger merges the ticket branch into the integration branch; the architect posts the evidence
   comment and closes the ticket. `ratchet/` does not move here: it is regenerated once, in the
   spec gate (§6);
7. the frontier is recomputed and freed writer slots are refilled at once.

Up to three tickets are in flight at any time (`AGENTS.md`, Concurrency).

## 5. Finishing a spec

1. Verifier: full build and tests on the integration branch (`VERIFIED_PASS` required).
2. `/code-review` against `main`: Standards (`docs/CODING_STANDARDS.md` + the smell baseline) and
   Spec (the spec issue), in parallel, never merged or reranked.
3. One implementer fixes every finding in one pass; the verifier re-runs the full gate.
4. `/pr` writes the PR body (summary, evidence, merge danger); the PR closes the spec.
5. CI green on the PR → merge commit to `main` (history of tickets kept). Never squash, never force-push.
6. `agent/HANDOFF.md` is rewritten in the same PR.

## 6. Checks, ratchet, CI

**Status: starting point.** ADR-0014 (ticket 14) has replaced the build, gate and CI parts of this
section. Ticket 15 (oracles and ratchet) decides the rest, and its ADR replaces the text below. The shape is carried over from flottila-mapping, where
it was measured. The build skeleton ticket of spec 1 adds `scripts/gate-ticket.sh <ticket>
<modules> [integration-ref]`, `scripts/gate-spec.sh <spec>`, `scripts/ratchet.java` and
`scripts/pit-summary.sh` by porting them from `subapavel/flottila-mapping` (same names, same log
conventions) and deleting what jqj has no use for. `scripts/check-trailers.sh`,
`scripts/check-lf-checkout.sh`, `scripts/push-retry.sh` and `scripts/clean-worktrees.sh` are
already here. Until the gate scripts exist the verifier runs the steps by hand, in this order.

Commands run from the repo root of the worktree. Logs go to `.logs/<ticket>-<check>.log`, never to
`target/` (`./mvnw clean` deletes it).

**Ticket gate** (verifier, per ticket, in its worktree; `<modules>` = the touched module
directories, comma-separated, e.g. `query-core,jq-core`):

| # | Command | Log | When |
| --- | --- | --- | --- |
| 1 | `./mvnw -B -pl <modules>,oracle -am verify` | `.logs/<t>-build.log` | always: tests and JaCoCo floors of touched and upstream modules, and every oracle the ratchet lists |
| 1b | PIT on the class-C modules among `<modules>`, then `scripts/pit-summary.sh ratchet/mutation.tsv <modules>` | `.logs/<t>-pit.log` | a class-C module is touched: more survivors than its `ratchet/` row is `EXECUTED_FAIL`; none touched prints `NOT_RUN` |
| 2 | `./mvnw -B -q -DskipTests verify` | `.logs/<t>-compile-all.log` | always: the whole reactor compiles; Spotless, enforcer, licences |
| 2b | `scripts/check-lf-checkout.sh` | `.logs/<t>-lf-checkout.log` | always: every tracked file checks out byte-identical on Windows (`.gitattributes`) |
| 2c | `scripts/check-trailers.sh <integration-ref>..HEAD` | `.logs/<t>-trailers.log` | always: every commit the ticket adds carries the §11 trailers, merges of the integration tip included |
| 3 | `java scripts/ratchet.java check` | `.logs/<t>-ratchet.log` | always: no listed case failing or missing, no coverage below `ratchet/` of the integration branch; unbuilt modules print `NOT_RUN` |

The ticket-gate budget on the VM (4 vCPU, three gates in parallel) is measured by the build
skeleton ticket and recorded in `bench/RESULTS.md`; the target is under 60 s for step 1.

**Spec gate** (verifier, integration branch, one at a time):

1. `./mvnw -B clean verify -Pbench`: all tests, all oracles (the differential oracle needs the
   reference jq binary, `docs/agents/environment.md`), JaCoCo floors, benchmarks compile;
   1b. PIT on the class-C modules the spec touched → `.logs/<spec>-pit.log`, one summary line per
   module;
2. the JMH scenarios, alone on the VM, with `-prof gc`, result file `.logs/<spec>-jmh.json`, and
   the same scenarios through the reference jq binary for the comparison line.
   - Results go to `bench/RESULTS.md` with machine label `claude-cloud-4vcpu`, the date and the
     commit. The score is the median of the forks.
   - A scenario worse than `ratchet/bench.tsv` by more than its tolerance is measured once more.
     The gate fails if it is still worse, or if a `docs/GATE.md` speed or memory target is missed.
3. `java scripts/ratchet.java regen --all`: every report must be present, a drop fails, the
   verifier alone commits `ratchet/` to the integration branch.

Regen reads the reports of steps 1, 1b and 2, so it runs last.

**CI** (GitHub Actions, added by the build skeleton ticket; every PR and push to `main`; required
on `main`): `trailers` (`scripts/check-trailers.sh` over the PR range, plus the script tests that
need no JDK) and `build` (`./mvnw -B -Pbench verify` with the reference jq binary installed). A
nightly workflow runs PIT on `main` over the class-C modules and commits nothing. A manual
workflow runs JMH, informative only: benchmark regression is a spec-gate check on the VM, never
on CI. Runners use Temurin 25.

**Ratchet** (`ratchet/`, format in `ratchet/README.md`): oracle pass lists, coverage and mutation
per module, benchmark baselines. Ticket gates check against the integration branch's copy, which
equals `main`'s during a spec; the spec gate regenerates it and the verifier commits it. A drop is
a failure; a rise is recorded at regeneration. Writers never touch it.

**Floors** (fixed in the build; draft until Phase 0 ticket 15): JaCoCo lines ≥ 90 % and branches
≥ 80 % on `query-core` and `jq-core`, lines ≥ 80 % on `jq-cli`; PIT ≥ 80 % on the class-C modules.

## 7. Decisions, findings, owner questions

| Kind | Example | Where |
| --- | --- | --- |
| reversible, local | an internal shape, a threshold, a library minor version | a line in the ticket's evidence comment |
| hard to reverse, surprising, real trade-off | tape layout, IR shape, the emit protocol, a public type | ADR in `docs/adr/` (`/domain-modeling` rules), draft by the designer |
| contradiction with an ADR or the architecture | the IR cannot express X as decided | finding: `docs/findings/F-<n>.md`, smallest change keeping the intent, ADR amended or superseded in the same merge, PR body says "deviation" |
| owner only | money, credentials, licences, a gate missed twice, history rewrite | issue labelled `ready-for-human`; the ticket waits, the run continues with the next frontier ticket |

## 8. Autonomous runs

`/run-spec` runs without asking the owner. A blocked ticket (owner answer, credential, network,
a tool failure that survives one retry and one alternative route) gets a comment and the
`ready-for-human` label, and the run takes the next frontier ticket. The run ends on
`SPEC_DONE` (PR merged), `IDLE_NO_FRONTIER` (everything left is blocked) or a quota stop; the
reason is the first line of `agent/HANDOFF.md`.

The cloud VM can be reclaimed at any time. Pushed branches, issues and `agent/HANDOFF.md` are the
recovery point: push the integration branch after every merge and every ticket branch at every
hand-back.

## 9. Files

| File | Purpose | Written by |
| --- | --- | --- |
| `agent/HANDOFF.md` | ≤ 20 lines of ≤ 160 chars: state, current spec and integration branch, tickets in flight with their worktree branches, next exact action, open owner issues | architect |
| `agent/NOTES.md` | module notes (commands, quirks, shapes) by section, read on demand | architect |
| `bench/RESULTS.md` | every measured number with machine, date, configuration, commit | architect, from verifier output |
| `ratchet/` | what passes, per oracle and module | verifier only |
| `docs/adr/` | binding decisions | architect (designer drafts) |
| `docs/findings/` | contradictions and their resolution | architect |
| `.claude/agent-memory/<name>/MEMORY.md` | durable lessons per agent | that agent |

GitHub Issues replace the backlog and the progress journal: the spec, its tickets and their
evidence comments are the record. Nothing that matters lives only in the conversation.

## 10. Turn economy (lessons from Flottila and flottila-mapping)

- The architect is the most expensive context; deep design goes to a fresh designer, not into it.
- Wait for a single agent in the foreground; background only with two or more in flight.
- A resumed agent keeps its context: a fix, a second review round or a partial hand-back resumes
  the same agent by name (`SendMessage`), never a fresh one.
- A fix packet names file, line, evidence and the sentence to write; nothing else.
- A Stop-hook reminder mid-run is declined in one line; it never triggers a commit of an
  unreviewed tree. `.logs/.batch-in-review` silences it while a review is running.
- Self-check lists in the writers mirror the reviewers' memory, so authors catch what reviewers
  would.

## 11. Commits and branches

- `main` ← PR per spec. `spec/<issue>-<slug>` ← merger. `t/<ticket>-<slug>` ← one implementer.
  Throwaway: `research/<name>`, `prototype/<name>` (never merged).
- Conventional Commits, scope = module without prefix: `feat(jq-core): …`, `test(oracle): …`,
  `fix(query-core): …`, `chore(ci): …`, `docs: …`.
- Body: what and why, then the evidence line. Trailers in order: `Ticket: #<n>`,
  `Co-authored-by: Claude <noreply@anthropic.com>`, `Signed-off-by: <owner>` (DCO; identity from
  the environment's git variables, never written into the repo). Write the trailer block in the
  message (`git commit -F <file>`); `-s` would add a second `Signed-off-by`.

## 12. After a run

`/retro` on the session: navigation, missing automated checks, standards the reviewers should
enforce, steering that does nothing, expensive tool calls. Mechanical lessons become checks
(build rule, enforcer rule, CI job); judgement lessons go to `docs/CODING_STANDARDS.md`;
`CLAUDE.md` stays a page of pointers. Every few specs: `/improve-codebase-architecture`.
