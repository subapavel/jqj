# Ratchet

What passes, per oracle and per module. **The format is ADR-0020's** (its Ratchet section wins over
the table below where they differ); the build skeleton ticket ports `scripts/ratchet.java`.
Regenerated and committed only by the verifier, on an integration branch: the oracle pass lists
after every merge, everything in the spec gate (`docs/WORKFLOW.md` §6). Never edited by hand. A
drop fails the gate.

## Files

All files are line-based, sorted, UTF-8, LF. Columns are separated by one tab.

| File | One line per | Columns |
| --- | --- | --- |
| `oracle/jqtest.txt` | passing case of jq's own test files | case id |
| `oracle/differential.txt` | case where our output equals the reference jq binary's | case id |
| `oracle/compile.txt` | case where the optimised and the conservative compile agree | case id, def |
| `oracle/backend.txt` | case where both backends agree | case id, def |
| `oracle/json.txt` | passing JSON conformance case | case id |
| `coverage.tsv` | module | `module`, `lines %`, `branches %` |
| `mutation.tsv` | class-C module | `module`, `surviving mutants`, `generated mutants` |
| `bench.tsv` | benchmark scenario | `scenario`, `machine label`, `score`, `unit` |

Percentages are whole numbers, rounded down. A benchmark baseline moves only on a gain beyond its
tolerance; lowering one takes a finding.

## Commands (once `scripts/ratchet.java` exists)

- `java scripts/ratchet.java check` (ticket gate): exit 1 if a listed case fails, is skipped or
  is missing, or a percentage is below its line. A module the gate did not build prints `NOT_RUN`.
- `java scripts/ratchet.java regen --oracles` (after every merge): the oracle pass lists only.
- `java scripts/ratchet.java regen --all` (spec gate): requires every report, refuses any drop,
  writes the files, prints the diff.

## Inputs of `scripts/ratchet.java`

`RATCHET_ROOT` (default: the current directory), `RATCHET_JMH` (default
`bench/jmh/target/jmh-result.json`), `RATCHET_MACHINE` (label of the `bench.tsv` lines).

- **Oracle reports** `oracle/target/oracle-reports/<oracle>-<suffix>.tsv`: `id`, verdict (`PASS`,
  `FAIL`, `STALE`, `CRASH`), detail, then the extra columns. The suffix is the pass list's `backend`
  (jqtest, differential) or `mode` (compile) column; `backend` and `json` ignore it. Extras: `def` is
  column 4 (differential, compile, backend), `label` column 4 and `def` column 5 (json). A listed id
  whose verdict is not `PASS`, whose extra column changed, or that is missing, fails.
- **Coverage**: JaCoCo `jacoco.csv`; class-C modules from `oracle/target/site/jacoco-aggregate/` rows
  whose `GROUP` is the module, any other module from `<module>/target/site/jacoco/`. A module without
  a report prints `NOT_RUN`.
- **Mutation**: `<module>/target/pit-reports/mutations.xml`. Killed is `KILLED`, `TIMED_OUT` or
  `MEMORY_ERROR`; everything else counts as surviving. `java scripts/ratchet.java floor-mutation
  <module>...` checks only the floor (ticket gate step 2).
- **Bench**: the JMH json result, one line per benchmark (`Class.method` without the class, plus
  `/param=value`). A `bench.tsv` line with an empty `tolerance%` is skipped by `check` and
  overwritten by `regen --all`; otherwise the baseline moves only on a gain beyond the tolerance and
  a loss beyond it is refused.
