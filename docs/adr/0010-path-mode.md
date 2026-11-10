# Path mode is a second variant over parent-linked paths

Status: proposed (2026-11-10, issue #7 "What is the execution protocol?")

Path mode is a second variant of the nodes. It is built only for the nodes that a TRACKED
`PathOf` or `Update` can reach (ADR-0007; WALK may run as TRACKED).

- **Types:** `interface PathSink { boolean emit(Object v, Path p); }` and the immutable
  `record Path(Path parent, Object key, int length)`. `null` means untracked.
- **Who passes the path on:**
  - Navigation steps extend the path.
  - `Identity`, `Pipe`, `Comma`, `If`, `Alt`, `Try`, `Label`, `Break`, `Bind`, `AltBind`, `Call`
    and `ParamCall` pass it on unchanged. Their conditions and bound values run in value mode.
  - `Foreach` binds its item as a (value, path) pair.
  - Every other node emits untracked.
- **The state of `reduce` and `foreach` stays untracked.** jq keeps one current path per
  evaluation and restores it each time it returns to the source (`gen_reduce`, `path_intact`).
  So a state path never survives an iteration (`[[1,2]] | path(reduce (0,1) as $i (.; .[$i]))`
  raises on jq-ref). The forms jq does accept are listed in `DEVIATIONS.md`.
- **Validity errors:** an untracked result used as a path raises "Invalid path expression with
  result …". The check is lazy for results, so `path(1|empty)` gives nothing. A navigation step on
  an untracked value raises at once, with jq's messages "…near attempt to access element … of …"
  and "…near attempt to iterate through …", and with jq's truncated value dump.
- **Shapes:**
  - FIXED updates run `getpath`/`setpath` on the constant chain.
  - WALK rebuilds along the steps with the Workspace path stack, and deletes apply after the walk.
  - TRACKED follows jq's `_modify`: the first right-hand result wins, a path whose right-hand side
    is empty goes to the delete list, and `delpaths` runs last. On jq-ref,
    `(.[0], .[1]) |= empty` gives `[3]` and `{"a":0} | .a |= (1,2)` gives `{"a":1}`.
- **The cost**, unmeasured: one `Path` object per navigation step per result, in TRACKED only.
  In return, nothing has to be saved or restored under break, `try`, the trampoline or a hand-over.

## Considered options

- **The Workspace path stack for TRACKED too:** no allocation, but every `Label`, `Try`, `AltBind`
  and trampoline frame must restore its height.
- **A path on every emit:** costs in value mode.
- **A mode flag inside one method, or an IR lowering to (value, path) pairs:** ADR-0006 keeps
  strategy out of the IR.
- **A tracked `reduce`/`foreach` state:** a (value, path) state in two node variants, which returns
  paths where jq raises. That direction is hard to take back.
