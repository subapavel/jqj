# Benchmark results

Every measured number with machine label, date, configuration and commit. A number that is not
in this file does not exist (`AGENTS.md`, Evidence vocabulary). Written by the architect from
verifier output; newest section first.

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

