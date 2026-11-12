# Ratchet

What passes, per oracle and per module, in the format of ADR-0020's Ratchet section as amended by
ADR-0023. `scripts/ratchet.java` reads and writes these files. Regenerated and committed only by the
verifier, on an integration branch: the oracle pass lists after every merge, everything in the spec
gate (`docs/WORKFLOW.md` §6). Never edited by hand. A drop fails the gate.

## Files

All files are line-based, UTF-8, LF. Columns are separated by one tab.

| File | One line per | Columns |
| --- | --- | --- |
| `oracle/jqtest.txt` | passing case of jq's own test files, per backend | `id`, `backend` |
| `oracle/differential.txt` | case where our output equals the reference jq binary's, per backend | `id`, `def`, `backend` |
| `oracle/compile.txt` | case where the optimised compile and the compile of that mode agree, per mode | `id`, `def`, `mode` (`conservative`, `copy-only`) |
| `oracle/backend.txt` | case where both backends agree | `id`, `def` |
| `oracle/json.txt` | passing JSON conformance case | `id`, `label`, `def` |
| `coverage.tsv` | module | `module`, `lines %`, `branches %` |
| `mutation.tsv` | class-C module | `module`, `surviving mutants`, `generated mutants` |
| `bench.tsv` | benchmark scenario and machine | `scenario`, `machine`, `score`, `unit`, `tolerance%` |

- **Oracle pass lists** have no column-name line. Their entries are sorted. A pass list may start
  with header lines: `# source <path> sha256 <hex>` for each copied source file and
  `# jq-ref <ver> sha256 <hex>`; `regen` refreshes the sha of a `# source` header. `compile.txt` and
  `backend.txt` are written once their oracles exist.
- **`coverage.tsv`, `mutation.tsv` and `bench.tsv`** start with one line of the column names above;
  the lines after it are sorted.
- **`bench.tsv`**: `score` is jqj's time as JMH reports it (ADR-0019), in `unit` (such as `us/op`),
  at most six decimals. `machine` is the label set by `RATCHET_MACHINE`. `tolerance%` is empty until
  spec 6's finding (ADR-0023).

Percentages are whole numbers, rounded down; the mutation score is computed from the two counts. A
benchmark baseline moves only on a gain beyond its tolerance; lowering one takes a finding.

## Commands

- `java scripts/ratchet.java check` (ticket gate): exit 1 if a listed case fails, is skipped or
  is missing, or a percentage is below its line or its floor. A module the gate did not build
  prints `NOT_RUN`.
- `java scripts/ratchet.java regen --oracles` (after every merge): the oracle pass lists only.
- `java scripts/ratchet.java regen --all` (spec gate): requires every report, refuses any drop,
  writes the files, prints the diff.
- `java scripts/ratchet.java floor-mutation <module>...`: checks only the PIT score of the modules
  against their floor. PIT left the ticket gate by F-27 and runs in the spec gate, whose
  `regen --all` checks the same floor.

## Inputs of `scripts/ratchet.java`

`RATCHET_ROOT` (default: the current directory), `RATCHET_JMH` (default
`bench/jmh/target/jmh-result.json`), `RATCHET_MACHINE` (the `machine` of the `bench.tsv` lines).

- **Oracle reports** `oracle/target/oracle-reports/<oracle>-<suffix>.tsv`: `id`, verdict (`PASS`,
  `FAIL`, `STALE`, `CRASH`), detail, then the extra columns. The suffix fills the pass list's
  `backend` (jqtest, differential) or `mode` (compile) column; `backend` and `json` ignore it. The
  pass list's `def` is report column 4 (differential, compile, backend); its `label` is column 4
  and its `def` column 5 (json). A listed id whose verdict is not `PASS`, whose `def` or `label`
  changed, or that is missing, fails.
- **Coverage**: JaCoCo `jacoco.csv`; class-C modules from `oracle/target/site/jacoco-aggregate/` rows
  whose `GROUP` is the module, any other module from `<module>/target/site/jacoco/`. A module without
  a report prints `NOT_RUN`.
- **Mutation**: `<module>/target/pit-reports/mutations.xml`. Killed is `KILLED`, `TIMED_OUT` or
  `MEMORY_ERROR`; everything else counts as surviving.
- **Bench**: the JMH json result, one line per benchmark (`Class.method` without the class, each `_`
  of the method read as `.`, plus `/param=value`). A `bench.tsv` line with an empty `tolerance%` is
  skipped by `check` and overwritten by `regen --all`; otherwise the baseline moves only on a gain
  beyond the tolerance and a loss beyond it is refused.
