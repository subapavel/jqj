# Milestone 1 gate — "jq filters run on JSON bytes, end to end"

**Draft, 2026-11-10.** Phase 0 map ticket 17 makes it final; until then every number marked
_(Phase 0)_ is open and each check may be reshaped by the ticket named beside it. Once final, the
gate-reviewer (`/gate`) verifies every check on one commit of `main`; each check names the
command and the evidence it needs, and a change to a check is a finding (`docs/WORKFLOW.md` §7).

## Checks

1. **Language.** The jq 1.8 language and builtins run: the cases of jq's own test files (879, copied
   under ADR-0021; ADR-0020's runner) pass in every backend the milestone ships and are listed in `ratchet/oracle/jqtest.txt` on the gate
   commit. The pass list covers the whole suite except the groups ticket 17 names _(Phase 0)_;
   every case left out is in `DEVIATIONS.md` with its reason. (Tickets 1, 15.)
2. **Agreement with jq.** On the differential cases (hand-written and generated: filter, input,
   flags) our output is byte-identical to the reference jq binary's; every case id is in
   `ratchet/oracle/differential.txt`; stdout, stderr and the exit code are compared, syntax-error
   text excepted (ADR-0020). The optimised and the conservative compile agree on the compile-mode
   cases (`ratchet/oracle/compile.txt`, ADR-0018); if the milestone ships both backends, they
   agree on the backend cases (`ratchet/oracle/backend.txt`). (Tickets 12, 15.)
3. **JSON.** The reader accepts and rejects what the reference jq does on every conformance case
   (`ratchet/oracle/json.txt`: JSONTestSuite inputs, jq-ref verdicts, ADR-0015), except the listed
   deviations, and an error carries jq's message, line and column and a byte-offset field. Untouched strings come out
   byte-identical; literal numbers come out as jq prints them: copied byte for byte when the
   token is canonical, otherwise in jq's canonical form (ADR-0012, F-1). Every input and output mode of ADR-0015
   has a differential case. (Tickets 9, 11.)
4. **Memory.** Each budget (recursion depth, bytes built, bytes written, steps, deadline) ends a filter as an uncatchable termination with a jq error message (ADR-0008, F-8), while the parse depth limit and the regex match time raise jq's catchable errors (F-13, ADR-0017), shown by a test per budget whose witness would otherwise exhaust the limit.
   On the JMH scenarios (ADR-0019): field extract allocates at most 1 KiB per operation more on
   10 MB than on 1 KB; accumulate grows at most 225× from 10⁴ to 10⁶ elements in time and
   allocation; G1, G2 and G6 `-c` on 10 MB complete in a 64 MiB fork. (Tickets 8, 12, 13.)
5. **Speed.** The gate scenarios of ADR-0019 run on the gate commit in spec-gate step 3 on
   `claude-cloud-4vcpu`, recorded in `bench/RESULTS.md` with machine, date and commit, beside the
   reference jq binary's time per document measured in the same session (`bench/jq-ref.sh`).
   Target: every G1–G6 cell at most 1.0× the reference time per document; cold start (G7) is
   recorded with no factor. No scenario is worse than `ratchet/bench.tsv` beyond its
   per-cell tolerance (set in spec 6 by a finding, ADR-0019).
   (Tickets 4, 12.)
6. **Command line.** `jqj` runs a filter over files and standard input with jq's main flags
   (ticket 1 lists them), with jq's exit codes; shown by differential cases that go through the
   command line. (Tickets 1, 13.)
7. **Embedding.** The public API of ticket 13 is in place: a compiled filter is immutable and
   safe to share between threads (a concurrency test), unloads when dropped (a test that observes
   the filter collected; its hidden class once bytecode exists, ADR-0018), and under the embedding defaults cannot read the environment or the
   system time zone, name a file, halt the JVM or import from disk (one test per rule, each with a witness filter).
   `import` resolves through the host. (Ticket 13.)
8. **Quality.** The CI run of the gate commit is green in every required job. The spec gate
   (`scripts/gate-spec.sh`) passes on the VM. Floors on the gate commit: JaCoCo lines ≥ 90 % and
   branches ≥ 80 % on `query-core` and `jq-core`, lines ≥ 80 % on `jq-cli`; PIT ≥ 80 % on the
   class-C modules, killed over all generated mutants (the nightly run of the gate commit;
   `quality/floors.tsv`, ADR-0020). The
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
| 6 | Speed | The jq-ref timing script, the speed factors and the bench rules in the ratchet (the bench module and each spec's scenarios exist from spec 1, ADR-0019), the analyses that buy the fast paths. Milestone 1 ships the interpreter only (ADR-0018). | 4, 5 |
| 7 | Embedding and command line | The public API and host SPI, sandbox defaults, `import`, the remaining flags. Its last ticket writes `HANDOVER.md`. | 6, 7, 9 |

## Out of the gate

The Flottila adapter, an XML front end, a native image of the command line, a Vector API scanner,
publishing to Maven Central.
