# Milestone 1 gate — "jq filters run on JSON bytes, end to end"

**Draft, 2026-11-10.** Phase 0 map ticket 17 makes it final; until then every number marked
_(Phase 0)_ is open and each check may be reshaped by the ticket named beside it. Once final, the
gate-reviewer (`/gate`) verifies every check on one commit of `main`; each check names the
command and the evidence it needs, and a change to a check is a finding (`docs/WORKFLOW.md` §7).

## Checks

1. **Language.** The jq 1.8 language and builtins run: the cases of jq's own test files pass in
   every backend the milestone ships and are listed in `ratchet/oracle/jqtest.txt` on the gate
   commit. The pass list covers the whole suite except the groups ticket 17 names _(Phase 0)_;
   every case left out is in `DEVIATIONS.md` with its reason. (Tickets 1, 15.)
2. **Agreement with jq.** On the differential cases (hand-written and generated: filter, input,
   flags) our output is byte-identical to the reference jq binary's; every case id is in
   `ratchet/oracle/differential.txt`. If the milestone ships both backends, they agree on the
   backend cases (`ratchet/oracle/backend.txt`). (Tickets 12, 15.)
3. **JSON.** The reader accepts and rejects exactly what the conformance cases say
   (`ratchet/oracle/json.txt`), and an error names the byte position. Untouched numbers and
   strings come out byte-identical. Every input and output mode of the founding architecture §10
   has a differential case. (Tickets 9, 11.)
4. **Memory.** Each budget (nesting depth, recursion depth, bytes built, bytes written, steps,
   deadline) ends a filter as an uncatchable termination with a jq error message (ADR-0008, F-8), shown by a test per budget whose witness would otherwise exhaust the limit.
   On the JMH scenarios: field extract allocates no more on 10 MB than on 1 KB beyond the
   tolerance _(Phase 0)_; accumulate is linear in the input; a large document completes in a
   fork with a small fixed heap _(Phase 0: size)_. (Tickets 8, 12.)
5. **Speed.** The JMH scenarios of the founding architecture §12 run on the gate commit in
   spec-gate step 2 on `claude-cloud-4vcpu`, recorded in `bench/RESULTS.md` with machine, date
   and commit, beside the reference jq binary's time for the same work measured on the same
   machine. Target: every scenario within the factor of the reference that ticket 12 fixes
   _(Phase 0)_. No scenario is worse than `ratchet/bench.tsv` beyond its tolerance.
   (Tickets 4, 12.)
6. **Command line.** `jqj` runs a filter over files and standard input with jq's main flags
   (ticket 1 lists them), with jq's exit codes; shown by differential cases that go through the
   command line. (Tickets 1, 13.)
7. **Embedding.** The public API of ticket 13 is in place: a compiled filter is immutable and
   safe to share between threads (a concurrency test), unloads when dropped (a test that observes
   the class collected), and under the embedding defaults cannot read the environment, name a
   file, halt the JVM or import from disk (one test per rule, each with a witness filter).
   `import` resolves through the host. (Ticket 13.)
8. **Quality.** The CI run of the gate commit is green in every required job. The spec gate
   (`scripts/gate-spec.sh`) passes on the VM. Floors on the gate commit: JaCoCo lines ≥ 90 % and
   branches ≥ 80 % on `query-core` and `jq-core`, lines ≥ 80 % on `jq-cli`; PIT ≥ 80 % on the
   class-C modules (the nightly run of the gate commit) _(Phase 0: ticket 15 confirms)_. The
   ratchet is green (`java scripts/ratchet.java check`). `query-core` has no reference to JSON or
   jq (a build rule). Every spec of the milestone was merged through `/run-spec` with its review.
9. **Hand-over.** `HANDOVER.md` covers: building and running from a clean clone with only JDK 25
   installed; running a filter from the command line and from Java; how to run the ticket gate,
   the spec gate and the benchmarks; what the Flottila adapter needs (the API of check 7, the
   sandbox defaults, `needsWholeDocument`); open findings, ADRs to revisit and the next three
   specs. The gate-reviewer follows the start instructions on a fresh worktree and records the
   result.

## Specs in build order

Draft; ticket 17 decides. Milestone 1 is built as a **walking skeleton** first, then widened axis
by axis; each spec goes through `/grill-with-docs` → `/to-spec` → `/to-tickets` → `/run-spec` in
this order, and no spec issue exists before its `/to-spec`. Check 8 holds after every spec. Every
ticket that adds a builtin or a construct also adds its oracle cases.

| # | Spec | Scope | Proves |
| --- | --- | --- | --- |
| 1 | Walking skeleton | The build skeleton ticket first: modules with `module-info`, the oracle module, `scripts/ratchet.java` and the gate scripts ported from flottila-mapping, JaCoCo, PIT in a nightly workflow, an empty `ratchet/`, CI, branch protection; it measures the ticket-gate budget. Then the thinnest path through every layer: `.` and `.a` over one JSON document — reader, tape, parser, IR, one backend, writer, the command line — with one jq-test case and one differential case in the ratchet. | 8; first slices of 1, 2, 3, 6 |
| 2 | Core language | Paths, pipes, comma, literals, object and array construction, operators, comparisons and sort order, variables and destructuring, `if`, `try`, alternative, `reduce`, `foreach`, `label`, function definitions and closures, recursion as ticket 7 decided. | 1, 2 |
| 3 | Builtins | The prelude and the native builtins by family (collections, strings, regex with joni, dates, math, formats `@…`), each with its oracle cases. | 1, 2 |
| 4 | Paths and updates | `path()`, `paths`, `getpath` family, `del`, the assignment operators, the ownership rule and its property tests. | 1, 2, 4 |
| 5 | JSON in full | Input and output modes, number formatting, limits and budgets, automatic streaming. | 3, 4 |
| 6 | Speed | The benchmark module and its scenarios, the analyses that buy the fast paths, the second backend if the milestone ships both, the bench rules in the ratchet. | 4, 5 |
| 7 | Embedding and command line | The public API and host SPI, sandbox defaults, `import`, the remaining flags. Its last ticket writes `HANDOVER.md`. | 6, 7, 9 |

## Out of the gate

The Flottila adapter, an XML front end, a native image of the command line, a Vector API scanner,
publishing to Maven Central.
