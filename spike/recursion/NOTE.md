# Spike A — does a self-calling jq function survive a big input? (issue #3)

Throwaway. Branch `spike/recursion-depth`, never merged. Code `src/Spike.java`, filters
`filters/*.jq`, raw output `results/*.log`, numbers in `bench/RESULTS.md` ("Spike A").

## What was run

Four filters over an input array of N integers (N = recursion depth), each returning the sum:
**T** self tail call, **E** self call inside "for each result" (jq's own `recurse(f)` shape),
**M** two functions calling each other in tail position, **N** non-tail recursion (`$a[.] + (.+1|s)`).
A push-style tree evaluator (`emit` returns "keep going", single-result nodes evaluated directly)
in five variants: **stack** (Java stack, default 1 MB), **tailloop** (a call in tail position
whose left context yields exactly one value returns a jump to the caller's call loop; self and
mutual), **tramp** (continuation-passing evaluator, continuations on the heap, driver loop;
`bounce=0` returns to the driver on every step, `bounce=64` after 64 direct steps),
**bigstack** (stack variant on a new platform thread with a 1 GB stack), **virtual** (stack variant
on a virtual thread). Results checked against jq-ref 1.8.2 (499500 at N=1000 for all four).

## Which cases work

| | T tail | E each | M mutual | N non-tail |
| --- | --- | --- | --- | --- |
| jq-ref 1.8.2, N=10⁶ | ok, 1.6 s, 93 MB | ok, 1.2 s, 93 MB | ok, 1.7 s, 151 MB | ok, 1.0 s, 267 MB |
| stack / virtual, warm max N | ~10 700 | ~3 900 | ~2 000 | ~11 700 |
| stack, cold first run | ok 10³, fails 10⁴ | **fails at 10³** | ok 10³, fails 10⁴ | ok 10³, fails 10⁴ |
| tailloop | **ok 10⁶, 51 ms** | as stack | **ok 10⁶, 44 ms** | as stack |
| tramp(64) | ok 10⁶, 197 ms | ok 10⁶, 192 ms | ok 10⁶, 231 ms | ok 10⁶, 328 ms |
| bigstack 1 GB | ok 10⁶, 127 ms | ok 10⁶, 168 ms | ok 10⁶, 136 ms | ok 10⁶, 116 ms |

(warm medians of 15 runs at N=10⁶; jq-ref times include ~0.23 s of parsing; the spike's values are
`Long`/`Object[]` with no JSON reading, so its absolute speed is not comparable to jq's.)

## Clean errors

Every failure was a `StackOverflowError` caught at the evaluation boundary; the JVM went on and
the next evaluation in the same JVM worked (all runs in `results/3-spike.log`). But the failure
depth is **not a property of the filter**: it moves ~3× with JIT warm-up (E fails at 1 000 cold,
works to ~3 900 warm) and with frame shape (M ~2 000, N ~11 700 on the same stack).
A virtual thread gives no more depth than a 1 MB platform thread (same numbers).

## What each fix costs

- **Tail call → jump**: the cheapest and the fastest variant at depth (≈45–50 ns per level);
  on shallow inputs ~1.4–1.5× the plain stack for T and M (0.31 vs 0.21 ms at N=10³), none for N.
  Fixes only T and M; E and N are untouched.
- **Trampoline**: fixes all four, but every call and every result pays: 1.6–4.8× slower than the
  plain stack on shallow inputs (T 0.94 vs 0.21 ms at N=10³), 1.1–2.8× slower than bigstack at
  depth, RSS 290–600 MB at N=10⁶. It also means a second code shape (CPS) in both backends.
- **Large-stack thread**: fixes all four up to ≥4 000 000 levels with 256 MB–1 GB; at depth
  ~120–170 ns per level; RSS 300–450 MB at N=10⁶ (only touched stack pages are committed).
  Cost on shallow inputs is the thread start (0.68 vs 0.21 ms at N=10³ without pooling).
  Depth per stack size: 64 MB → 465 000 (E) to 836 000 (N) levels warm.

## Recommendation (for the owner to react to; becomes an ADR through "What is the execution protocol?")

1. **Tail calls in a single-result context compile to jumps** (self: a loop in the method;
   mutual: a call loop). Covers T and M at the best speed of all variants.
2. **A call-depth budget** (founding architecture §6.6) turns deep recursion into a deterministic
   jq error ("recursion too deep") at a depth independent of the JIT; `StackOverflowError` is
   caught only as a backstop. Cost of the counter: NOT_RUN (not measured in this spike).
3. **No trampoline.** Filters the analysis finds with non-tail recursion (E, N shapes; by inference also
   prelude functions like `walk`, not measured) **run on a platform thread with a large stack** (size and
   pooling decided in "What is the execution protocol?"); all other filters stay on the caller's (virtual) thread.

Open for the owner: is "deep non-tail recursion works up to a budget, then a clean error" enough,
or must jqj match jq's "bounded only by memory"? The second needs the trampoline everywhere.
