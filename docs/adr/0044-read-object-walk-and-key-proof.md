# A read object is walked only through its key proof

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #85; designer draft; the owner chose option D on #82); amends ADR-0002 (duplicate keys), ADR-0008 (the proof's ticks), ADR-0030 (RunState scratch), ADR-0034 (Many loop slots), ADR-0037 (line 11's walk citation) and ADR-0042 (`to_entries` native); closes F-40

A walk over a read object's members costs n² key comparisons (F-40): 39.9 s for `-c .` on 78 841
keys, against 0.06 s on jq-ref. Proving the keys distinct in the reader made every read pay (#82,
over its cost bar). The owner chose to prove them on the walk side instead.

## Decision

- **`ObjectView` is no `Members`.** Every walk of a read object begins with
  `KeyProof.walk(RunState, ObjectView, boolean written)`, which returns an
  `ObjectWalk(Document doc, int index, int size, int[] plan)` that implements `Members` and `Keyed`.
  Its cursor is the key's tape index when `plan` is null, or a position in `plan` (pairs of a key
  index and its last value's index) when a key repeats. A walker that skips the proof fails
  loudly, not slowly. `ObjectView` keeps `Keyed.get`; a field read scans as before and pays
  nothing.
- **Walkers**: `.[]`, Equality's left side (its right side's count becomes `size`), Order's key
  lists, ObjectMerge (both sides), the Printer, Detach (replaces its TreeMap), `keys`,
  `keys_unsorted`, `length`, `flatten`, `to_entries` (now a native, replacing the prelude definition), and the
  host accessors `size()`/`forEach`.
- **`.[]` holds its walk in a loop slot**: `Many.loopSlots()` (default 0, Iterate 1);
  `IntrinsicManyOp` allocates `args.length + loopSlots` per loop.
- **The proof**: pairwise compares up to 8 keys; otherwise open addressing on decoded key bytes in
  `RunState.ints(n)`, a reused `int[]` (query-core, format-neutral), a power of two ≥ 4·n slots
  capped at 2^20, with a probe budget of 4·n for the whole proof; a probe that compares two keys
  of one length weighs max(1, KiB of the key) against that budget (#85), so long colliding keys
  reach the sort, not 4·n full-length compares. Over the budget (crafted
  collisions) it sorts the key ordinals by unsigned decoded bytes with jqj's stable merge sort
  (ADR-0012, F-48); equal neighbours are repeats. Worst case O(key bytes + n log n), never n². The
  first repeat starts the plan: first place, last value, as jq.
- **The knowledge lives as long as its walk.** A second walk proves again (at most twice a linear
  walk). Not per view (views are made anew), not per document (an Owned document is shared across
  threads; the tape stays read-only and tag 10 stays reserved), not per run (it would grow with the
  input and make step counts depend on history).
- **Ticks** (ADR-0008): one per key entry, max(1, started KiB) for a long key, and one per
  fallback compare, max(1, KiB of the shorter key); the Printer charges through `tickWritten`. Inside a run every caller that prints a read object
  (`tojson`, `tostring`, `@text`, interpolation) charges the run's state, not a throwaway one (#85).
  Host accessors (`size()`, `forEach`) run outside any run, like Detach (ADR-0016), and charge none.
- **Hot-path rule** (`docs/CODING_STANDARDS.md`): a native's position scratch within one call, and
  the plan of a repeated-key object held by `.[]` while it emits, are not collections between
  pipeline stages: they hold tape positions, never values.
- **Out of scope**: Equality's `get` per member on the right side (a follow-up `KeyIndex` from the
  same proof code), and BuiltObject's #83 index on `String.hashCode`.

## Considered options

- **Proof in the reader (tag 10)**: #82, +12 % at 100 KB, +26 % at 10 MB, 2.59× on a flat object;
  over its cost bar.
- **A memo per view, per document or per run**: see the decision.
- **A scan for repeated keys**: leaves a crafted n² on `.[]`.
- **Sort only**: n log n on every wide walk.
- **A seeded hash**: step counts would vary between runs.
- **A trie or radix sort**: linear worst case, but much more code to save a log factor.
- **Lowering Iterate with an input argument**: touches the IR #87 changes, and a root `.[]` would
  read as a whole-input read.
- **Long cursors in `Members`**: every walker changes, and the plan still has nowhere to live.
