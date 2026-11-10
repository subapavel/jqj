# Benchmark results

Every measured number with machine label, date, configuration and commit. A number that is not
in this file does not exist (`AGENTS.md`, Evidence vocabulary). Written by the architect from
verifier output; newest section first.

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

