# Spec 2 "Core language" — grilling record

Input for `/to-spec`. Owner answers, round by round. Drafts: designer (round 1), architect (later
rounds); every round evaluated by the `grilling-adviser` first.

## Round 1 (owner: yes to all eight, 2026-11-12)

1. **Split 2a / 2b at recursion.** 2a: run protocol with the Workspace, the full parser, every
   non-recursive construct, non-recursive `def` and closures inlined with an inline node cap from
   the first ticket (above it a Stub until 2b), the prelude loader and the natives its cases need,
   the steps / deadline / bytes-built / bytes-written budgets, core-v1, the compile-mode runner, G2
   and G4 at 10⁴ in `bench.tsv`. 2b: recursive groups, call kinds, form-B trampoline,
   recursion-depth budget, `stackBackstopIsTermination`; cardinality + may-break-out + value form,
   folding, specialisation, tail calls; U calibration; diagnostics. Census: about 402 jq-test cases
   for 2a, 26 for 2b; the 9 recursive `builtin.jq` definitions (`recurse/1`, `recurse/2`,
   `_flatten/1`, `while/2`, `until/2`, `repeat/1`, `combinations/0`, `tostream/0`, `walk/1`) are
   Stubs until 2b.
2. **Bytes built per slot:** ADR-0017 amendment (8 B / element, 16 B / entry, UTF-8 length, a
   measured fixed charge per built container and string).
3. **Conservative compile first** behind `CompileMode` from 2a's first ticket; each analysis its own
   class-C ticket in 2b (red: facts test + two-sided cardinality property test); the compile-mode
   oracle after every merge (F-29).
4. **Lazy prelude:** jq 1.8.2 `builtin.jq` copied byte for byte (notice, `NOTICE`), parsed once per
   JVM on the first compile naming a non-native builtin, transitive closure only; jqj natives win
   by name and arity. 2a's parser accepts the whole file (update operators lowered to a Stub until
   spec 3; `$$$$v`).
5. **jq-test target = named case list per spec gate** from a case census script over jq-ref's
   disassembly.
6. **core-v1** as drafted (typed, depth 4, spec 2's constructs plus `length type not empty error
   tostring select map range/2 first limit` and one bounded recursive `def`; seeds [0, 2100);
   recorded whole at 2a's start; excludes breaks across `try`/`?`/`?//`, sort cycles, deep
   recursion incl. `..` and `range/3`, syntax errors, `$__loc__`, `input`; ≥ 1000 cases by 2b's
   gate) plus a jq-ref distribution dry run the owner approves before the freeze.
7. **U calibration in 2b** without path-mode `..` (F-30).
8. **Compare and merge depth** as jq (ADR-0029).

Not owner questions (architect): retro order #49 → #50, #47 before class-C tickets, #51–#55 in
parallel; owner approves the setup-script changes of #50 and #54. Settled by ADRs: holds before
spec 5, `explain()` timing, compile-mode `conservative` lines, case per construct, 1 KB bench
cases. Minor finding F-31 (ADR-0006 natives wording).
