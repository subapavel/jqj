# Owner review, Monday 2026-11-16

Under the owner's delegation of 2026-11-14, the grilling-adviser answered and approved for the owner
in the grillings of specs 2b, 3, 4 and 5. This page lists what comes back to the owner. Each item
names its record; the reasons are there. A "no" on an item reopens that record, not the spec.

## Owner-only (not decided under the delegation)

1. **Licence, issue #105.** May code under the SunPro (fdlibm) notice, or CORE-MATH's once its licence
   is read, be translated for 11 math builtins (`erfc`, `gamma`, `lgamma`, `lgamma_r`, `tgamma`,
   `j0`, `j1`, `jn`, `y0`, `y1`, `yn`)? Until then the fallback is published formulas (DLMF), in
   one ticket (#130). A yes needs a licence-exception ADR.
2. **GATE check 8** names only jq's MIT text in the jar; Arm's MIT text must ship too once `erf`
   lands (ADR-0051). Changing a check is the owner's.
3. **Repository renames** `subapavel/flottila` and `flottila-mapping` on GitHub, and whether history
   that still holds the old name is rewritten. Nothing is rewritten without the owner's word.

## Spec 2b (#106; `docs/wayfinder/spec-2b-grilling.md`)

- GATE check 2's compile-mode list gains the `trampoline` run (F-58).
- No IR fold pass, reversing 2a round 1's "folding in 2b" (F-57).
- The embedded recursion-depth default stays 4 194 304 until the calibration's heap numbers exist
  (ADR-0046).
- The pending jump carries closure arguments (ADR-0047).
- `stackBytes` 0 is a public setting: everything runs on the trampoline (ADR-0046).

## Spec 3 (`docs/wayfinder/spec-3-grilling.md`; not yet published)

- ADR-0010's Bind rule ("bound values run in value mode") gives way to `Tracking` (F-59, ADR-0048).
- `debug` and `stderr` arrive in spec 3a, not in specs 5 and 7; GATE check 2 says so (F-61).
- ADR-0023's spec 3 row and GATE row 3: the 3a/3b split, G5 and the own owner in 3a.
- Spec 4 may start once 3a is on main, beside 3b (ADR-0023's order).

## Spec 4 (#120; `docs/wayfinder/spec-4-grilling.md`)

- The last digits of 36 math builtins are jqj's own, the same on every platform, each within a
  measured ulp bound (ADR-0051, F-65). jq-ref's own digits change with the CPU.
- Arm's `erf` is ported under MIT (its dual licence's listed alternative); Arm enters NOTICE.
- ADR-0017: date names from fixed English tables, `strptime` and `strftime` read the host zone for
  `%s`, the regex limit per search call (ADR-0052, ADR-0053, F-66, F-67).
- GATE rows 2 and 4 (G3 in 2a, F-33) and check 2's host-facing list (ADR-0023), changed with no
  finding of their own.
- The designer's choices the adviser approved: LRU eviction and a run-scoped identity in the pattern
  cache, `fromjson`'s charge rule, the 25/18/6/12 math lists, the `jn`/`yn` order conversion, F-70's
  row in the strings ticket, far instants at the zone's standard offset after jq-ref's last daylight
  instant.

## Spec 5 (`docs/wayfinder/spec-5-grilling.md`; not yet published)

- ADR-0016 (F-71): `Workspace.read(JsonInputs)`, `-n` leaves `InputOptions`, the `OutputOptions`
  withers.
- ADR-0018, ADR-0007 and the glossary (F-77): a pipe's right side is a hold; the conservative
  compile detaches every `input` document at its read.
- ADR-0002 and ADR-0012 (F-73): NONFINITE is never written.
- ADR-0015: `-R` copies jq's per-block UTF-8 quirk, which loses a byte (F-75); `--seq`'s silent cut
  only after whitespace (F-74).
- ADR-0023: the 5a/5b split; jq.test 2315, 2319, 2324, 2498 and 2563 are spec 5's (F-76).
- ADR-0054: host streaming saves memory only on an array target, and uses one reused element tape
  where founding §6.7 says "each with its own small tape".
