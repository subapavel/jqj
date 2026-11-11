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
| `oracle/backend.txt` | case where both backends agree | case id |
| `oracle/json.txt` | passing JSON conformance case | case id |
| `coverage.tsv` | module | `module`, `lines %`, `branches %` |
| `mutation.tsv` | class-C module | `module`, `surviving mutants`, `generated mutants` |
| `bench.tsv` | benchmark scenario | `scenario`, `machine label`, `score`, `unit` |

Percentages are whole numbers, rounded down. A benchmark baseline moves only on a gain beyond its
tolerance; lowering one takes a finding.

## Commands (once `scripts/ratchet.java` exists)

- `java scripts/ratchet.java check` (ticket gate): exit 1 if a listed case fails, is skipped or
  is missing, or a percentage is below its line. A module the gate did not build prints `NOT_RUN`.
- `java scripts/ratchet.java regen --all` (spec gate): requires every report, refuses any drop,
  writes the files, prints the diff.
