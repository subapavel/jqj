# Spike B — is reading one field from a big message fast enough? (issue #4)

Throwaway. Branch `spike/field-read`, never merged. Code `src/spike/*.java`, raw output
`results/`, numbers in `bench/RESULTS.md` ("Spike B").

## What was run

Read `.target` from a root object of message-like records (strings with escapes and non-ASCII,
numbers, nested objects and arrays). Documents are 969 B, 105 KB and 10.7 MB, with the field at
the start, in the middle or at the end of the root object. There are five variants, all plain byte
loops (no 8-bytes-at-a-time scanning):

- **tapeReused**: one validating pass fills a `long[]` tape (tag | span | offset), then the root
  entries are walked (one jump per container). The tape is kept between reads, as a `Workspace`
  would keep it.
- **tapeFresh**: the same, with a new tape per read.
- **validateOnly**: the same validating pass with no tape writes. It notes the last root match as
  it goes. This is the cheapest reader that still behaves like jq.
- **skipAll**: a forward cursor that reads every root key and steps over values by bracket
  matching, without validating them.
- **stopFirst**: the same cursor, returning at the first match.

JMH 1.37 with 2 forks × 5 × 1 s, after 3 × 1 s warm-up, plus `-prof gc`. All variants gave the
same value as `jq-ref -c .target` on all nine documents.

## Numbers (µs per read; allocation per read)

| document | tapeReused | tapeFresh | validateOnly | skipAll | stopFirst | jq-ref (process) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 KB, any position | 2.0–2.1, 0 B | 3.7–3.9, 8 KB | 1.8–1.9 | 1.3–1.5 | 0.02 / 0.62 / 1.35 | 2.2 ms (≈ start-up 2.4 ms) |
| 100 KB, any position | 233–249, 2 B | 327–355, 516 KB | 212–224 | 146–153 | 0.02 / 87 / 158 | 5.0 ms |
| 10 MB, any position | 33 300–35 000 | 40 100–41 100, 33.5 MB | 22 300–24 400 | 14 500–16 400 | 0.02 / 8 200 / 15 900 | 451–465 ms, 174 MB RSS |

Throughput at 10 MB: tape ~320 MB/s, validate ~480 MB/s, skip ~730 MB/s. The tape for the 10.7 MB
document is **33.5 MB**, about 4.2 M entries at 8 bytes each, or 3× the input. "Bytes plus one
array" can therefore mean bytes × 4 for dense small values.

## What changes behaviour (observed, `results/4-check.log`)

| input | jq-ref | tape / validate | skipAll | stopFirst |
| --- | --- | --- | --- | --- |
| `{"target":1,…,"target":3}` | `3` | `3` | `3` | **`1`** |
| `{"target":1,"b":[1,2` (cut off) | parse error | error | error | **`1`** |
| `{"target":1,"b":tru}` | parse error | error | **`1`** | **`1`** |
| `{"b":tru,"target":1}` | parse error | error | **`1`** | **`1`** |

Only the validating readers behave like jq. **skipAll** accepts a bad value anywhere.
**stopFirst** also returns the first duplicate and ignores a broken tail.

## Recommendation (for the owner to react to)

1. **One reading mode in milestone 1: the validating tape, reused from the `Workspace`.** It
   behaves like jq. Its cost over the cheapest jq-compatible reader (validateOnly) is ~1.1× at
   1–100 KB and ~1.5× at 10 MB. A fresh tape per read costs another 1.2–1.8×, so the tape must
   be reused.
2. **No second light mode now.** The only big win (stopFirst: a field at the start in 20 ns
   instead of 2 µs–35 ms) comes from not reading the rest of the document. That changes results
   (duplicate keys, malformed input), so it is a deviation from jq, not an optimisation. If
   Flottila wants it, it is an explicit opt-in ("lenient read"), decided later with a
   `DEVIATIONS.md` entry.
3. **For "What are the value model and the tape layout?"**: the tape size (3× the input here)
   counts towards the memory budget. A compact entry, or entries only for containers and touched
   scalars, are options to weigh there. The reader's own speed (8-byte scanning) is still
   untried, and it lifts every variant alike.

Open for the owner: must jqj always validate the whole input as jq does (one mode), or should
Flottila be able to ask for a lenient read that stops at the field?
