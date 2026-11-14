# Analysis contracts

Status: accepted (2026-11-10, owner, issue #6 "What shape does the IR have, and which analyses run on it?"); amended by ADR-0008 (issue #7); amended by ADR-0022 (issue #8); fact keys by ADR-0045 and cardinality's promise by F-56 (spec 2b); holds by F-77 and root use by ADR-0054 (spec 5)

Analyses run once on a frozen program and write an immutable side table of facts, keyed by a dense
node id: freeze numbers each function's nodes in pre-order into an identity map, and each fact is an
array by id (ADR-0045). Nothing rewrites a frozen program. An analysis reads only node kinds and
`Operation` fields. **A missed fact costs speed or raises an error, never a wrong value.**

- **Cardinality**: none, exactly one, at most one or many, for every node and function, computed as
  a least fixpoint per recursive group. An error or a break is not a result. "Exactly one" and "at
  most one" also promise that nothing runs after the result; a node that may run on after it is
  "many" (F-56).
  - **The guarantee is two-sided.** "Exactly one" promises one result unless an error or break
    ends the node, so a backend reads it without testing. A property test checks both bounds
    against the interpreter.
- **Path shape**, for every `Update` and `PathOf`:
  - FIXED: a constant key or index chain;
  - WALK: field, index and iterate steps, with guards that yield at most one result;
  - TRACKED: everything else.

  WALK is used only where it equals jq's `reduce path(lhs)` form, and a backend may always run WALK
  as TRACKED. The conditions come from jq-ref:
  - deletes apply after the walk: `(.[0], .[1]) |= empty` → `[3]`;
  - null-creating steps: `null | .a[1].b |= 5` → `{"a":[null,{"b":5}]}`;
  - quiet steps skip: `3 | .a? |= 5` → `3`;
  - an error leaves no partial result.
- **Path validity is an explicit rule.** A path result comes from the tracked position through
  navigation steps and input-preserving control flow, plus the `foreach` item variable that
  `limit`, `skip` and `nth` need. Anything else raises "Invalid path expression", including cases
  jq accepts: `path(. as [$a] | $a)` (jq `[0]`), `path(.a | . as $v | $v)` (jq `["a"]`) and
  `{"a":true} | path(.a | true)` (jq `["a"]`; on `{"a":1}` jq raises too, F-10). These are listed
  in `DEVIATIONS.md`.
- **Root use** reports only whether a filter is *eligible* to stream (`<fixed path> | .[] | rest`,
  where `rest` uses no outer slot and does not read input). Streaming is off unless the host turns
  it on. By default jq-ref prints nothing when a document has a late syntax error, and takes the
  last of duplicate keys.
- **Holds and retention** (amends ADR-0004): `input` documents get their own Workspace tape, so the
  main document stays valid for the whole evaluation (`. as $o | input | [$o, .]` → `[1,2]` on
  jq-ref).
  - A value that may view an input document is detached only when it enters a hold that spans a
    later read: a variable, an accumulator, an array being built, an error payload, or a closure
    or group that reads.
  - An accumulator detaches only its new part, so `reduce inputs as $x ([]; . + [$x])` stays linear.
  - A missed hold raises the stale-view error.
- **Last read**: a slot whose value is read exactly once per binding is marked, so "When may a
  value be updated in place?" may treat that binding as a move, not an escape.
- **Touched fields** is a contract only. Its consumer would be a reader that leaves untouched
  subtrees off the tape, which ADR-0002 does not allow yet. It is built only when "How do the JSON
  reader and writer behave?" adopts that.
- **Constant folding** (the parse-time fold; there is no IR fold, F-57) never folds an impure
  operation or one that reads input. An operation that
  throws while folding stays in place: `1/0` is a run-time error in jq.

## Considered options

- **Facts as fields in the nodes**: every pass would rebuild all nodes.
- **Upper-bound-only cardinality**: "exactly one" would then equal "at most one", and every read
  would need a test.
- **An identity test at each variable read for path validity**: it reproduces jq on `$v` but not on
  destructuring or literals, and built numbers have no identity (ADR-0003).
- **Automatic streaming** (founding §3/§6.7): whether a malformed document gives partial results or
  none would depend on whether the analysis happens to succeed.
- **One shared tape for `input`**: wrong for `. as $o | input | [$o, .]`.

## Amendment (ADR-0008, issue #7)

- **May break out** is one more fact computed per recursive group, next to cardinality. A subtree
  that holds a `Break` to an outer label, any `ParamCall`, or a call to a function that does either
  runs in the push form, never the value form.
- The `reduce` and `foreach` state stays untracked in path mode (ADR-0010).
- The path-validity row for `path(.a | true)` holds only on `{"a":true}` (F-10).

## Amendment (ADR-0022, issue #8)

- **Last read** means read at most once per binding activation, with no loop body (a reduce or
  foreach update or extract, an update's right side, a recursive body) and no context with more
  than one result between the binding and the read.
- **WALK key steps:** a dynamic step (ADR-0005) whose key has at most one result and does not read
  `.` is a WALK step; an object key at run time is a slice (ADR-0022).
- **The in-place licence** is a new fact. Like cardinality, an over-claim gives a wrong value.

## Amendment (spec 5 round 1; F-77, ADR-0054)

- **A hold** is every place a value stays live across a node that may read input, that node's own
  `.` included; this replaces the list of holds above. So a pipe's right side holds its input.
  Required cases: `input | [., input]` → `[1,2]`; on stdin `1 2 3`,
  `def f(g): [g, g]; input | f(., input)` → `[1,2,1,3]`.
- **May read input** is a per-group fact, computed as may-break-out is (the ADR-0008 amendment
  above), from the `reads` fact spec 3 puts on Operation. Holds add no IR kind. A kind that proves
  needed ships its heap form (ADR-0046) and its path variant.
- **Spec 5a detaches every `input` document at its read**, in both compiles. Spec 5b adds the hold
  analysis to the optimised compile; the conservative compile keeps detaching at each read
  (ADR-0018).
- **Root use** is ADR-0054's syntactic test; a filter the test does not prove eligible has
  `needsWholeDocument()` true.
