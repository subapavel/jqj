# Benchmark results

Every measured number with machine label, date, configuration and commit. A number that is not
in this file does not exist (`AGENTS.md`, Evidence vocabulary). Written by the architect from
verifier output; newest section first.

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

