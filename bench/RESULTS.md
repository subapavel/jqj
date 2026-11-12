# Benchmark results

Every measured number with machine label, date, configuration and commit. A number that is not
in this file does not exist (`AGENTS.md`, Evidence vocabulary). Written by the architect from
verifier output; newest section first.

## Ticket-gate steps 1 and 2 and PIT per 1000 seeds (ticket #40, F-19), 2026-11-12

- Machine: `claude-cloud-4vcpu` (4 vCPU, 15 GiB, Linux 6.18.44-fc-v378), `java version
  "25.0.5.0.3" 2026-10-25 LTS`, jq-ref `jq-1.8.2`. Date 2026-11-12, 02:31Z to 03:39Z. Nothing else of
  this ticket's agent ran during a measurement; other worktrees' load was not checked, `uptime` before
  the parallel start read 1.30 (1 min, the tail of the run before it).
- Commit measured: `eee8bb3`, the tip of `spec/22-walking-skeleton`, every other ticket of spec 1
  merged. Each run used a fresh detached worktree of it (no `target/`), removed afterwards.
- Gate command, per run in a fresh worktree: `scripts/gate-ticket.sh <label> jq-core,oracle
  3e9a9d3^1`, `3e9a9d3^1` = `7e7aac5`, the commit before #35's merge. Eight main source files changed
  between it and the tip in jq-core and oracle (`JsonStrings`, `Printer`, `BuiltString`, `ObjectView`,
  `StringView`, `JsonReader`, `Workspace`, `DiffReport`), so PIT mutates 642 mutants in jq-core and
  661 in oracle. `jq-cli` is not in the module list (`Input.java` changed there).
  Times are the wall-clock ms the gate writes to `.logs/<label>-steps.tsv`; copies in
  `.logs/40-<label>-steps.tsv`, gate output in `.logs/40-<label>-gate.log`, step logs in
  `.logs/40-<label>-wtlogs/`. Every step 1 and 2 exited 0.
- Passing cases at the tip (`ratchet/oracle/*.txt`): **jqtest 10, differential 629 (of which
  `gen/values-v1` 472, hand-written 157), json 125**. The `values-v1` file holds seeds [0, 2100); 472
  pass, the rest are run only by the Survey, which PIT excludes. Required cases that `verify` ran:
  `DifferentialOracleTest` 630 (629 + 1), `JsonOracleTest` 126, `JqTestOracleTest` 11 (each one case
  more than the list); their elapsed time was 0.478 s, 0.345 s, 0.041 s.
- There is no compile-mode oracle yet (no test class, no `compile.txt`): step 1 measures the
  interpreter's oracles only.

**Step 1 (`./mvnw -B -Pbench verify`) and step 2 (PIT), alone and three gates in parallel**

| run | step 1 verify | target | step 2 PIT | target |
| --- | --- | --- | --- | --- |
| alone, run 1 (`m40a1`, 02:31Z) | 30.1 s | 60 s (three in parallel) | 741.8 s (12 min 22 s) | 3 min (three in parallel) |
| alone, run 2 (`m40a2`, 02:44Z) | 27.4 s | 60 s | 728.0 s (12 min 8 s) | 3 min |
| three in parallel, gate 1 (`m40b1`) | 70.3 s | 60 s | 1052.9 s (17 min 33 s) | 3 min |
| three in parallel, gate 2 (`m40b2`) | 71.4 s | 60 s | 1077.1 s (17 min 57 s) | 3 min |
| three in parallel, gate 3 (`m40b3`) | 70.2 s | 60 s | 1072.2 s (17 min 52 s) | 3 min |

- The three gates started together at 02:57:36Z and the last ended at 03:16:49Z (whole gate
  1129.0 s, 1152.6 s, 1146.9 s). Three distinct labels, three worktrees, one shared `~/.m2`.
- **Step 1 is over its 60 s target with three in parallel (70.2 to 71.4 s), under it alone (27.4 to
  30.1 s).** The oracle tests themselves are about 2.7 s of it (the three required-case classes above
  plus the three Surveys, 0.2 s to 1.1 s each, in `m40a1`); the rest is the reactor build (alone: jq-core 7.5 s, jq-cli
  5.5 s, oracle 7.2 s, bench/jmh 3.1 s, query-core 3.8 s). ADR-0014/ADR-0020's named fallback is the
  compile-mode oracle moving to the spec gate; none exists in step 1 yet, so it would not shorten this
  number. The architect decides.
- **Step 2 is over its 3-minute target alone (728.0 and 741.8 s, 4.0 to 4.1 times the target) and
  with three in parallel (1052.9 to 1077.1 s, about 5.9 to 6.0 times).** PIT per module, alone:
  jq-core 205 s and 193 s, oracle 510 s and 511 s (`Completed in …`, `.logs/40-m40a*-wtlogs/m40a*-pit.log`);
  in parallel jq-core 324 to 332 s, oracle 675 to 688 s. The oracle module's PIT mutates the same
  jq-core classes against the oracle module's 232 test classes (661 mutants, 49 482 to 50 399 test
  runs), after jq-core's own run (642 mutants, 44 050 to 44 194 test runs).

**PIT cost per 1000 passing `values-v1` seeds**

How: the same gate on the same commit, with the 472 `gen/values-v1/*` lines deleted from
`ratchet/oracle/differential.txt` in the scratch worktree only (never committed, `ratchet/` untouched
in the ticket), so PIT runs 157 differential cases instead of 629 and nothing else changes. The
step-2 time with the seeds minus the step-2 time without them, divided by 472, scaled to 1000. The
runs `m40c1`/`m40c2` stopped at step `lf` (the edit is an unstaged change); steps 1 and 2 had run.

| run | step 2 PIT | jq-core module | oracle module |
| --- | --- | --- | --- |
| with 472 seeds, `m40a1` / `m40a2` | 741.8 s / 728.0 s | 205 s / 193 s | 510 s / 511 s |
| without them, `m40c1` / `m40c2` | 532.0 s / 537.4 s | 202 s / 191 s | 304 s / 323 s |

- Mean with 734.9 s, mean without 534.7 s: the 472 seeds add **200.2 s**, **0.424 s per seed, 424 s
  per 1000 passing seeds (7 min 4 s)**. Range over the four pairs: 190.6 s to 209.8 s, 404 s to 444 s
  per 1000. The scale is a straight line from 472 seeds to 1000; it was not measured at 1000 (only 472
  pass). The extra cost is all in the oracle module (about 197 s; jq-core's module is the same with or
  without them).
- Against the target: 424 s per 1000 seeds alone is 2.4 times the whole 3-minute budget, and the
  step without any seed is already 534.7 s alone (3.0 times it). So the seeds are not the only cause
  here: ADR-0020's first fallback (group the seeds per unit, or keep them out of PIT) would at most
  take 734.9 s down to 534.7 s on this ticket, which is still over 3 minutes. The next fallback in
  ADR-0020 and ADR-0014 is PIT leaving the ticket gate, by a finding. The architect decides.
- Not isolated: the per-seed cost with three gates in parallel.

**Recording `values-v1` (F-19: about 9.1 s to record and about 11 s to verify per 1000 seeds)**

`scripts/oracle-record.sh` in a fresh worktree of the tip, three runs each, wall clock including the
script's `javac` of the recorder and the JVM start (`.logs/40-rec.tsv`, `.logs/40-rec-*.log`):

| command | runs | against F-19 |
| --- | --- | --- |
| `--generate values-v1 0 1000` (1000 cases, 0 dropped) | 10.09 s, 9.97 s, 10.13 s | about 9.1 s per 1000 |
| `--verify …/gen/values-v1.rec` of those 1000 | 10.19 s, 10.31 s, 10.65 s | about 11 s per 1000 |
| `--verify …/gen/values-v1.rec` as committed (2100 seeds) | 18.61 s, 18.64 s, 18.43 s | about 11 s per 1000 (so 23 s) |

Both are far under the 10-minute bound the ticket set for re-measuring.

**Recording `core-v1` (ticket #58, ADR-0033), 2026-11-12**

`scripts/oracle-record.sh` in the `t/58-core-v1` worktree (52e54b1 merged with `spec/56-core-language-2a` at
56174c7), the cloud container (4 vCPU Intel Xeon @ 2.30 GHz, load average 1.4–2.1), jq-ref 1.8.2, three runs
each, wall clock including the script's `javac` and the JVM start (`.logs/58-record.log`, `.logs/58-record3.log`).
All 2100 seeds recorded, 0 dropped; the three recordings are byte-identical.

| command | runs | per 1000 seeds |
| --- | --- | --- |
| `--generate core-v1 0 2100` (2100 cases, 0 dropped) | 14.68 s, 16.20 s, 15.77 s | about 7.3 s |
| `--verify …/gen/core-v1.rec` (2100 seeds) | 15.31 s, 15.86 s, 15.63 s | about 7.4 s |

## Bench recording G1, G6, G7: jqj against jq-ref (ticket #39), 2026-11-12

- Machine: `claude-cloud-4vcpu` (4 vCPU Intel Xeon Processor @ 2.10GHz, Linux 6.18.44-fc-v378),
  `java version "25.0.5.0.3" 2026-10-25 LTS`, jq-ref `jq-1.8.2`. Date 2026-11-12, session 02:09Z to
  02:11Z, no other command of this ticket's agent running (other worktrees' load not checked). Commit `2c0f444` on `t/39-bench-recording` (the commit that adds
  `BenchCasesTest` and the G6/G7 cases; no code under measurement changed after the #38 tip `93840f6`).
- Commands, from the repo root after `./mvnw -B -Pbench -DskipTests package` (`.logs/39-package.log`),
  in one shell script (`.logs/39-session-*`): `java -jar bench/jmh/target/benchmarks.jar -foe true -prof gc
  -rf json` (`.logs/39-jmh-session.log`, `.logs/39-jmh-result.json`), then `bench/jq-ref.sh`
  (`.logs/39-jq-ref-session.log`), then the small-heap forks below. G7's process half ran
  `jq-cli/target/jqj -c .a` through the default `jqj.launcher`.
- Flag: `-foe <bool>` (fail on error) is in the jar's own `-h` (`.logs/39-jmh-help.log`); rc 0 with it
  on means no scenario threw. All seven ids ran with the annotations' settings (avgt, 2 forks, 3 × 1 s
  warm-up, 5 × 1 s measurement, 2 GiB heap; G7 single-shot, 10 forks, no warm-up).
- Inputs as in the #30 section below (`orders`, 1 KiB / 100 KiB / 10 MiB). jq-ref as ADR-0019 says
  (best of 5, K copies, minus start-up).
- G7.engine.1k: `.a` on the 1 KB document runs; JMH with `-foe true` exited 0 over its 10 forks.
- Factor = jqj JMH score / jq-ref per document, in the same unit. A factor above 1.0 would break gate
  check 5 in spec 6; **none of the five G1/G6 cells is above 1.0**. G7 has no factor (ADR-0019); the
  ratio is shown for the record only.

| id | jqj (JMH, ± 99.9 % error) | jq-ref per document | factor jqj/jq-ref |
| --- | --- | --- | --- |
| G1.1k | 2.492 ± 0.136 µs | 21.746 µs | 0.115 |
| G1.100k | 219.652 ± 16.790 µs | 2.348 ms | 0.094 |
| G1.10m | 23.639 ± 2.048 ms | 391.4 ms | 0.060 |
| G6.compact.10m | 53.416 ± 4.304 ms | 538.9 ms | 0.099 |
| G6.pretty.10m | 60.352 ± 3.167 ms | 687.8 ms | 0.088 |
| G7.engine.1k | 18.289 ± 4.126 ms (single shot, 10 forks) | 22.144 µs | no factor (ratio 826) |
| G7.process.1k | 120.493 ± 11.990 ms (single shot, 10 forks) | 2.387 ms (one process) | no factor (ratio 50.5) |

jq-ref columns behind the table (`bench/jq-ref.sh`): K, best, start-up (ms) = G1.1k 10000, 219.889,
2.432; G1.100k 100, 237.046, 2.293; G1.10m 1, 393.728, 2.359; G6.compact.10m 1, 541.317, 2.430;
G6.pretty.10m 1, 690.264, 2.422; G7.engine.1k 10000, 223.836, 2.400; G7.process.1k one process, 2.387.
Allocation (`-prof gc`, B/op): G1.1k 320.0, G1.100k 321.5, G1.10m 547.0, G6.compact.10m 627.9,
G6.pretty.10m 674.2, G7.engine.1k 2 214 792, G7.process.1k 1 317 285. Gate check 4's flat allocation
reads G1.10m − G1.1k = 227 B/op against the limit of 1 KiB (observed here, not asserted by any test).

**Small-heap forks of gate check 4** (`java -jar bench/jmh/target/benchmarks.jar 'G1_10m|G6_compact_10m'
-foe true -jvmArgs "-Xms64m -Xmx64m" -prof gc -rf json`; `.logs/39-jmh-smallheap.log`, the log's
"VM options" line reads `-Xms64m -Xmx64m`): both forks complete, rc 0, no exception.

| id | 64 MiB fork | same cell at 2 GiB | factor of the 64 MiB fork |
| --- | --- | --- | --- |
| G1.10m | 24.282 ± 1.425 ms, 551.2 B/op | 23.639 ms | 0.062 |
| G6.compact.10m | 51.777 ± 1.718 ms, 616.1 B/op | 53.416 ms | 0.096 |

The factor of the 64 MiB rows uses the jq-ref time of the table above. Gate check 4 also names G2
here; G2 enters the bench in spec 2.

## Bench module and jq-ref.sh: jq-ref per document (ticket #30), 2026-11-11

- Machine: `claude-cloud-4vcpu` (4 vCPU Intel Xeon Processor @ 2.10GHz, Linux 6.18.44-fc-v378),
  `java version "25.0.5.0.3" 2026-10-25 LTS`, jq-ref `jq-1.8.2`. Date 2026-11-11T17:06Z.
- Commit: `44de834` on `t/30-bench-module`. Command: `java -jar bench/jmh/target/benchmarks.jar -prof
  gc -rf json`, then `bench/jq-ref.sh` straight after in the same shell (`.logs/30-jmh-session.log`,
  `.logs/30-jq-ref-session.log`).
- JMH against the Stubs: the six API scenarios end every fork in `JqCompileException` (`jqj stub:
  JqEngine.compile …`); `G7.process.1k` ends in `IOException`, because `jq-cli/target/jqj` does not
  exist yet. No jqj number exists.
- Inputs: `orders` of `Inputs` (bench module). Sizes are 1024-based: `1k` = 1 KiB = 1 024 bytes,
  `100k` = 100 KiB = 102 400 bytes, `10m` = 10 MiB = 10 485 760 bytes, exactly; SHA-256 pinned in
  `InputsTest`. They are not the inputs of issue #12, so the rows below do not compare
  with the "Reference times" table under it.
- jq-ref per ADR-0019: one discarded run, best of 5, K concatenated copies, minus the best of 5
  start-up runs of the same filter on `/dev/null`, divided by K. `G7.engine.1k` uses that rule on
  `-c .a`; `G7.process.1k` is one whole process on one 1 KiB document, nothing subtracted.
- Columns: K is the number of concatenated copies per run; best and start-up are wall-clock
  milliseconds of one jq-ref process (best of 5); per document is (best − start-up) / K, printed in
  µs or ms as marked (the script prints µs). jqj's JMH units, once it has numbers: µs/op for the
  `avgt` scenarios (G1, G6), ms/op for the single-shot G7.

| id | filter | K | best | start-up | per document |
| --- | --- | --- | --- | --- | --- |
| G1.1k | `-c .a.b.c` | 10000 | 214.853 ms | 2.311 ms | 21.25 µs |
| G1.100k | `-c .a.b.c` | 100 | 244.976 ms | 2.348 ms | 2.426 ms |
| G1.10m | `-c .a.b.c` | 1 | 407.432 ms | 2.343 ms | 405.1 ms |
| G6.compact.10m | `-c .` | 1 | 548.518 ms | 2.367 ms | 546.2 ms |
| G6.pretty.10m | `.` | 1 | 689.134 ms | 2.423 ms | 686.7 ms |
| G7.engine.1k | `-c .a` | 10000 | 225.628 ms | 2.442 ms | 22.32 µs |
| G7.process.1k | `-c .a` | 1 | 2.352 ms | – | 2.352 ms (one process) |

**JMH flag spelling (ADR-0019, "UNVERIFIED" there).** Checked on JMH 1.37 from the jar's own `-h`,
`-lrf` and `-lprof` (`.logs/30-jmh-help.log`; Context7 is not offered in the implementer's session).
`-bm avgt -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json` is the right spelling: `-bm` takes
`avgt` and `ss`, `-w`/`-r` take a `<time>` such as `1s`, `-rf` lists `json`, `-lprof` lists `gc`.
A run of `G1_1k` with exactly those flags printed "Warmup: 3 iterations, 1 s each", "Measurement:
5 iterations, 1 s each", "Average time" and 2 forks (`.logs/30-jmh-flags.log`). Heap: `-jvmArgs`
(`-jvmArgsAppend`, `-jvmArgsPrepend`); the class's annotations carry these settings and G7's (`ss`,
10 forks, no warm-up, one iteration), and a flag given on the command line overrides them for every
benchmark, G7 included.

**How to run** (from the repo root, after `./mvnw -B -Pbench package`):

- Gate scenarios: `java -jar bench/jmh/target/benchmarks.jar -prof gc -rf json`, then
  `bench/jq-ref.sh` straight after, in the same shell.
- Small-heap fork of gate check 4 (G1 and G6 `-c` at 10 MiB; no extra ids, the same methods):
  `java -jar bench/jmh/target/benchmarks.jar 'G1_10m|G6_compact_10m' -jvmArgs "-Xms64m -Xmx64m" -prof gc -rf json`.
  NOT_RUN in #30 (the Stubs end every fork before a read); recorded in #39 (section above). Add
  `-foe true` to every recording run: without it a throwing benchmark exits 0.

## Build skeleton: one build alone and three in parallel (ticket #23), 2026-11-11

- Machine: `claude-cloud-4vcpu` (4 vCPU Intel Xeon @ 2.10GHz, 15 GB RAM, Linux 6.18), JDK 25.0.5,
  Maven 3.9.16 through the wrapper, `~/.m2` warm (shared, `file-lock`).
- Commit: `9c5c9f9` on `t/23-build-skeleton`. Command: `./mvnw -B -Pbench verify` (the whole
  reactor of ADR-0014 step 1: word check, enforcer, compile with `-Xlint:all -Werror`, tests,
  JaCoCo, licence check, jars, aggregate report), wall clock from the shell. No `clean` between
  runs after the first (the warm-up, from a fresh clone, is the clean figure). Three separate
  local clones of that commit, so the `target/` directories do not collide.
- The modules hold one placeholder class each, so these are the floor of the build, not a
  forecast. The numbers say how much three at a time cost, not how long a real gate takes.

| run | seconds |
| --- | --- |
| clean, alone (warm-up) | 7.4 |
| alone, 3 runs | 6.7, 6.8, 6.5 |
| three in parallel, round 1 | 14.9, 16.7, 17.0 |
| three in parallel, round 2 | 14.7, 15.0, 15.1 |
| three in parallel, round 3 | 14.7, 15.1, 15.1 |

Alone about 6.7 s; three at a time about 15 s each (2.2×), under ADR-0014's 60 s target for
step 1. PIT, the LF check and the trailer check were not part of these runs. PIT of the
placeholder in `oracle` with `crossModule`, whole `-am` reactor: 9.5 s (`.logs/23-pit-oracle.log`,
single run, not repeated three at a time; the first PIT run in `query-core` took 24.9 s because
it downloaded the plugin).

## Reference times and limits (issue #12), 2026-11-11

Observed by the grilling-adviser on `claude-cloud-4vcpu`, jq 1.8.2 (the reference jq), JDK 25.0.5,
joni 2.2.9. Inputs synthetic (ADR-0019's generators). Logs posted on issue #12. Bind ADR-0019.

**Reference jq, time per document** (best of 5 minus the best start-up run, divided by K):

| cell | K | per document |
| --- | --- | --- |
| G1 1K / 100K / 10M | 10000 / 100 / 1 | 16.46 µs / 1.670 ms / 376.2 ms |
| G2 1K / 100K / 10M | 10000 / 100 / 1 | 26.26 µs / 3.542 ms / 495.4 ms |
| G3 1K / 100K / 10M | 10000 / 100 / 1 | 95.48 µs / 10.26 ms / 1907 ms |
| G4 10⁴ / 10⁵ / 10⁶ | 100 / 10 / 1 | 5.714 ms / 49.63 ms / 660.1 ms |
| G5 1K / 100K / 10M | 10000 / 100 / 1 | 80.79 µs / 8.018 ms / 1196 ms |
| G6 `-c` 1K / 100K / 10M | 10000 / 100 / 1 | 32.27 µs / 3.837 ms / 476.0 ms |
| G6 pretty 10M | 1 | 560.8 ms |

- Start-up (`jq -n 1`): best 2.072 ms, median 2.214 ms. One process on one 1K document: 2.078 ms.
- Between two sessions the reference's best-of-5 moved by −29 % to +10 % (why the ratchet holds
  jqj's time, not the factor).
- JVM hello-world start: 22.9–24.0 ms (G7 has no factor).

**joni, `("a"*n+"b") | test("^(a+)+$")`** (one JVM in order; fresh JVM per n):

| n | warm median (min–max), one JVM | warm median (min–max), fresh JVM |
| --- | --- | --- |
| 22 | 203.9 ms (196.8–240.5) | 195.7 ms (146.1–198.4) |
| 23 | 436.4 ms (414.9–453.1) | 414.3 ms (396.5–421.4) |
| 24 | 828.3 ms (702.2–843.1) | 786.7 ms (582.4–840.0) |
| 25 | 1592 ms (1277–1809) | 1578 ms (1387–1730) |

n = 23 cold 442.5 ms, warm 436.4 ms → max(2 × warm, cold) = 872 ms → `regexMatchTime` 1 s.

**Small heap, reading a 10 MB document** (Spike B reader, tape 16 MiB):

| heap | G1 | Serial | Parallel |
| --- | --- | --- | --- |
| 64 MiB | both pass | both pass | both pass |
| 48 MiB | both pass | `Docs.make` OOM | both OOM |
| 40 MiB | `Docs.make` OOM | both OOM | both OOM |

The writer, G2 and the JMH harness in a small-heap fork: NOT_RUN.

**Spike E (hybrid recursion, medians of 15 reps, three rounds):** `each` 1000 levels 0.46–0.58 ms,
10⁶ levels 92–154 ms; `tail` 1000 levels 0.16–0.31 ms, 10⁶ levels 38–59 ms. Single runs reached
277 ms at 10⁶.

## Spike A extension: nested drivers (issue #7), 2026-11-10

- Same machine and JVM as "Spike A": OpenJDK 25.0.5, 4 vCPU, `-Xms2g -Xmx2g`, default 1 MB stack.
- Code: branch `spike/recursion-depth` @ `7fd4ea6`, variant `hybrid`, run with `-Dlimit=256`,
  `-Dbounce=64` and `-Dresume=false|true`. Raw output is in
  `spike/recursion/results/7-nested-drivers-v2.log` there.
- The first run (`0c6bffe`, `7-nested-drivers.log`) is superseded. In form B it dropped a result
  that `cps()` delivered before the first drive, so shape E gave `WRONG(32640)`. The
  grilling-adviser found this.
- Filter `nested`:

  ```
  def s: if . == $L then 0 else $a[.] + (. + 1 | s) end;
  def g: if . == $L then 0 else (. + 1 | g) as $x | $x + (($L - 1000) | s) end;
  0 | g
  ```

  It is a non-tail recursion of depth n whose continuation starts a second recursion of depth 1000
  at every level.
- The two forms of hand-over from the Java stack to the trampoline:
  - **Form A**, Spike A's `hybrid`: the caller's sink runs inside the driver.
  - **Form B**: the driver stops at each result and returns it to its starter. The starter emits
    the result from its own frame, then resumes the driver.

Cold JVM, one run each (ms; the last column is the most drivers live at once):

| form | nested n=2000 | nested n=10⁴ | E 10⁶ | N 10⁶ | T 10⁶ | M 10⁶ | drivers |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | SOE after 909 | SOE after 3214 | ok, 257 | ok, 699 | ok, 154 | ok, 188 | 201 / 202 on nested |
| B | ok, 983 | ok, 4049 | ok, 312 | ok, 746 | ok, 183 | ok, 164 | 1 |

Warm medians in ms, with min–max: 20 warm-up runs at n=1000, then 5 reps per n.

| shape | form | n=1000 | n=10⁵ | n=10⁶ |
| --- | --- | --- | --- | --- |
| T | A | 0.39 (0.31–0.41) | 14.94 (13.71–23.91) | 90.89 (43.98–146.25) |
| T | B | 0.31 (0.30–0.33) | 13.57 (9.30–20.18) | 76.78 (47.06–151.48) |
| E | A | 0.77 (0.72–0.81) | 21.84 (8.14–30.39) | 177.46 (164.97–284.57) |
| E | B | 0.89 (0.79–1.19) | 34.70 (11.57–37.05) | 227.96 (104.74–293.53) |
| M | A | 0.35 (0.34–0.55) | 12.10 (9.63–24.44) | 108.84 (73.63–158.33) |
| M | B | 0.54 (0.47–0.61) | 17.57 (15.18–31.98) | 90.35 (55.51–148.59) |
| N | A | 0.58 (0.54–0.60) | 33.89 (29.32–58.78) | 412.82 (248.48–601.41) |
| N | B | 0.56 (0.53–0.78) | 29.36 (23.95–55.92) | 450.80 (295.42–519.97) |

Form A overflows on `nested`, and form B completes it. On the four Spike A shapes, form B is
within the noise except E, the shape with many results per driver, which is about 1.3× at 10⁶.
ADR-0011 takes form B.

## Tape reader for milestone 1 (issue #5), 2026-11-10

- Same machine, JVM and JMH settings as "Spike B" (2 forks × 5 × 1 s, 3 × 1 s warm-up, `-prof gc`).
  Code: branch `spike/field-read` @ `011d4cf`, `spike/fieldread/src/spike/{TapeM1,M1Bench,CheckM1}.java`;
  raw output `spike/fieldread/results/5-m1.*` and `5-checkm1.log` there. Documents: `Docs.make`,
  field in the middle.
- `spikeBTape` = the Spike B tape (reused). `m1NoDup` = the #5 round 1 layout (container offset
  at the closing bracket) plus UTF-8 classification (invalid UTF-8 or DEL → escaped-string tag)
  and the 10 000 nesting limit. `m1Dup` = `m1NoDup` plus duplicate-key detection: linear compares
  up to 8 keys, a reused open-addressing table with an FNV hash above 8 keys.
- `CheckM1`: same entry counts as Spike B (185 / 19 997 / 2 049 843), `.target` found; duplicates
  detected below and above 8 keys; depth 10 000 accepted, 10 001 rejected; invalid UTF-8 → tag 6.

| µs/op (B/op) | 1 KB | 100 KB | 10 MB |
| --- | --- | --- | --- |
| spikeBTape | 2.094 ± 0.166 (0.015) | 239.457 ± 17.252 (1.7) | 33 422.727 ± 2 321.770 (229) |
| m1NoDup | 2.118 ± 0.148 (0.015) | 246.242 ± 21.131 (1.7) | 34 308.973 ± 2 804.665 (236) |
| m1Dup | 2.800 ± 0.122 (0.020) | 325.073 ± 26.434 (2.3) | 48 892.116 ± 5 878.920 (334) |

Duplicate detection in the reader: +32 % at 1 KB and 100 KB, +43 % at 10 MB over `m1NoDup`.
The root object has 8 / 770 / 78 841 keys in the three documents (counted over the same tape), so
the 10 MB cost is mostly one very wide object. `TapeM1`'s detector shares one table stamp across
nesting levels and compares raw key bytes; it is a timing prototype, not a template.
UTF-8 classification and the depth limit: +1–3 %, inside the error.

## Spike B — reading one field (issue #4), 2026-11-10

- Machine: Claude Code Cloud VM, 4 vCPU Intel Xeon @ 2.10GHz, 15 GB RAM, Linux 6.18.
- JVM: JDK 25.0.5 HotSpot, `-Xms2g -Xmx2g`, otherwise defaults.
- Code: branch `spike/field-read` @ `3a75399`, `spike/fieldread/run.sh`; raw output in
  `spike/fieldread/results/` there.
- JMH 1.37, `Mode.AverageTime`, 2 forks × 5 iterations × 1 s after 3 × 1 s warm-up, `-prof gc`
  (allocation = `gc.alloc.rate.norm`); error column is JMH's 99.9% interval.
- Documents (`Docs.make`): root object of records, 969 B / 107 765 B / 11 206 791 B, `.target`
  at the start, middle or end of the root object. Variants checked equal to `jq-ref -c .target`
  on all nine.
- Reference: `jq-ref` 1.8.2 `-c .target <file>`, best of 5 wall-clock runs incl. process start
  (start-up alone, `jq-ref -n 1`: 2.36 ms): 1 KB 2.20–2.27 ms, 100 KB 5.00–5.07 ms, 10 MB
  451.15–465.00 ms; max RSS 12.7 MB / 12.7 MB / 173.7 MB.

- Tape size (corrected 2026-11-10, `Count.java` over the same `Tape`/`Docs`, log
  `.logs/4-tapecount.log`): 185 / 19 997 / 2 049 843 entries for the 1 KB / 100 KB / 10 MB
  documents = 1 480 B / 159 976 B / 16 398 744 B of tape, 1.53× / 1.48× / 1.46× the input. The
  33.5 MB allocated by `tapeFresh` at 10 MB is the doubling from 1024 entries (2 097 152 capacity
  plus every smaller array), not the tape size.

Per read, µs/op and bytes allocated/op:

| document | tapeReused | tapeFresh | validateOnly | skipAll | stopFirst |
| --- | --- | --- | --- | --- | --- |
| 1KB start | 2.10 ± 0.14 µs, 0 B | 3.67 ± 0.37 µs, 8256 B | 1.91 ± 0.10 µs, 0 B | 1.45 ± 0.19 µs, 0 B | 0.02 ± 0.00 µs, 0 B |
| 1KB middle | 2.02 ± 0.11 µs, 0 B | 3.85 ± 0.22 µs, 8256 B | 1.83 ± 0.12 µs, 0 B | 1.35 ± 0.15 µs, 0 B | 0.62 ± 0.06 µs, 0 B |
| 1KB end | 2.09 ± 0.13 µs, 0 B | 3.72 ± 0.24 µs, 8256 B | 1.85 ± 0.09 µs, 0 B | 1.33 ± 0.18 µs, 0 B | 1.35 ± 0.15 µs, 0 B |
| 100KB start | 249.16 ± 28.58 µs, 2 B | 354.98 ± 36.84 µs, 516242 B | 212.06 ± 13.15 µs, 1 B | 152.63 ± 10.29 µs, 1 B | 0.02 ± 0.00 µs, 0 B |
| 100KB middle | 233.46 ± 16.91 µs, 2 B | 327.33 ± 17.67 µs, 516242 B | 214.26 ± 27.48 µs, 1 B | 146.04 ± 7.96 µs, 1 B | 86.57 ± 9.50 µs, 1 B |
| 100KB end | 240.97 ± 20.97 µs, 2 B | 328.21 ± 27.79 µs, 516242 B | 223.70 ± 22.24 µs, 2 B | 145.79 ± 5.34 µs, 1 B | 158.39 ± 18.68 µs, 1 B |
| 10MB start | 34869.82 ± 1782.14 µs, 238 B | 41112.84 ± 2379.01 µs, 33546762 B | 23145.91 ± 2334.44 µs, 159 B | 14601.04 ± 801.09 µs, 101 B | 0.02 ± 0.00 µs, 0 B |
| 10MB middle | 33292.47 ± 1327.09 µs, 227 B | 40120.80 ± 2940.32 µs, 33546756 B | 22266.71 ± 446.87 µs, 153 B | 14498.29 ± 633.05 µs, 100 B | 8206.26 ± 1058.41 µs, 57 B |
| 10MB end | 34993.23 ± 1212.77 µs, 240 B | 40188.93 ± 2348.23 µs, 33546757 B | 24373.64 ± 2135.80 µs, 168 B | 16364.04 ± 1128.59 µs, 113 B | 15927.43 ± 987.10 µs, 110 B |

## Spike A follow-up — hybrid stack + trampoline (issue #3), 2026-11-10

- Same machine, JVM and method as "Spike A" below. Code: branch `spike/recursion-depth` @ `4dd57d0`,
  variant `hybrid` (`-Dvariant=hybrid -Dbounce=64 -Dlimit=K`): tail calls as jumps (as
  `tailloop`), other calls on the Java stack (1 MB) up to K nested calls, a call beyond K runs on
  the CPS trampoline and hands its results back to the direct caller. Raw output
  `spike/recursion/results/3-spike-hybrid.log` there.

Time (ms, median; 5 reps, 15 at N=10⁶), T / E / M / N:

| K | N=1000 | N=10⁴ | N=10⁵ | N=10⁶ |
| --- | --- | --- | --- | --- |
| 256 | 0.31 / 0.89 / 0.31 / 0.61 | 2.62 / 3.30 / 2.83 / 4.36 | 8.99 / 11.12 / 13.63 / 44.49 | 47.07 / 132.17 / 52.34 / 350.12 |
| 1000 | 0.30 / SOE / 0.33 / SOE | 2.52 / SOE / 2.84 / SOE | 8.81 / SOE / 11.96 / 30.24 | 43.90 / SOE / 44.93 / 383.47 |

Cold first run (fresh JVM): K=256 all four ok at 10³ and 10⁴; K=1000 E and N SOE at 10³ and 10⁴.
K=256 at N=10⁶, cold: 131.19 / 262.98 / 141.72 / 622.94 ms, max RSS 160 / 305 / 191 / 365 MB.
Baselines measured in the same run at N=1000 (15 reps): stack T 0.21, M 0.23, N 0.27; tailloop T
0.29, M 0.31, N SOE (it was ok at 0.28 in the earlier run); bigstack 1 GB E 1.09, N 0.86, and at
N=10⁶ E 151.12, N 285.26.

## Spike A — recursion depth (issue #3), 2026-11-10

- Machine: Claude Code Cloud VM, 4 vCPU Intel Xeon @ 2.10GHz, 15 GB RAM, Linux 6.18.
- JVM: JDK 25.0.5 HotSpot, `-Xms2g -Xmx2g`, otherwise defaults (`ThreadStackSize` 1024 KB).
- Code: branch `spike/recursion-depth` @ `48b6c4b`, `spike/recursion/run.sh`; raw output
  `spike/recursion/results/*.log` there. Not JMH: a hand loop, 20 warm-up runs at N=1000, then
  the median of 5 (N ≤ 10⁵) or 15 (N = 10⁶) runs; spread is wide (see min/max in the logs).
- Reference: `/usr/local/bin/jq-ref` 1.8.2, best of 3 wall-clock runs incl. process start and
  parse (`jq-ref length` on the N=10⁶ file: 230 ms), max RSS via `wait4`.
- Filters (`spike/recursion/filters/`): T self tail call, E self call inside "for each result",
  M mutual tail calls, N non-tail; N = depth = input length.

Time at N=10⁶ (ms; spike = evaluation only, values are `Long`/`Object[]`, no JSON reading):

| variant | T | E | M | N |
| --- | --- | --- | --- | --- |
| jq-ref 1.8.2 (whole process) | 1636.8 | 1249.1 | 1702.2 | 987.5 |
| stack (1 MB) | SOE | SOE | SOE | SOE |
| virtual thread | SOE | SOE | SOE | SOE |
| tailloop | 50.61 | SOE | 44.30 | SOE |
| tramp, bounce 0 | 341.43 | 257.70 | 345.15 | 226.52 |
| tramp, bounce 64 | 196.72 | 191.61 | 230.92 | 327.92 |
| bigstack 1 GB | 126.51 | 168.26 | 135.87 | 116.17 |

Time at N=1000, warm (ms): stack 0.21 / SOE / 0.24 / 0.32; tailloop 0.31 / SOE / 0.34 / 0.28;
tramp(0) 0.94 / 0.93 / 1.09 / 0.51; tramp(64) 1.00 / 0.89 / 0.90 / 0.67; bigstack 0.68 / 1.00 /
0.68 / 1.00 (includes starting the thread); virtual 0.55 / SOE / 0.35 / 0.54.

Max depth after warm-up (binary search, ±1%): stack 1 MB and virtual thread T ~10 681, E ~3 907,
M ~2 030 (virtual 2 015), N ~11 780 (virtual 11 719); bigstack 64 MB T ~757 812, E ~464 843,
M ~695 312, N ~835 937; 256 MB T ≥ 4 000 000, E ~1 859 375, M ~2 781 250, N ~3 343 750;
1 GB all ≥ 4 000 000. Cold first run (fresh JVM), stack variant: T, M, N ok at 10³ and SOE at 10⁴;
E SOE at 10³.

Max RSS at N=10⁶ (MB), cold single run: jq-ref 93 / 93 / 151 / 267; tailloop 162 / – / 191 / –;
tramp(0) 605 / 478 / 542 / 364; tramp(64) 454 / 288 / 488 / 364; bigstack 304 / 413 / 450 / 313;
a JVM failing fast with SOE: 76.

