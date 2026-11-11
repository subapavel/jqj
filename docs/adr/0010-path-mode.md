# Path mode is a second variant over parent-linked paths

Status: accepted (2026-11-10, owner, issue #7 "What is the execution protocol?"); amended by ADR-0022 (issue #8: a licensed WALK writes in place)

Path mode is a second variant of the nodes. It is built only for the nodes that a TRACKED
`PathOf` or `Update` can reach (ADR-0007; WALK may run as TRACKED).

- **Types:** `interface PathSink { boolean emit(Object v, Path p); }` and the immutable
  `record Path(Path parent, Object key, int length)`. `null` means untracked, and the root is a
  non-null empty `Path`.
- **Who passes the path on:**
  - Navigation steps extend the path.
  - `Identity`, `Pipe`, `Comma`, `If`, `Alt`, `Try`, `Label`, `Break`, `Bind`, `AltBind`, `Call`
    and `ParamCall` pass it on unchanged. Their conditions and bound values run in value mode.
  - `Foreach` binds its item as a (value, path) pair.
  - An `Intrinsic` follows its operation's path rule (none, step or same; ADR-0005). For example,
    `{"a":1} | path(.a|debug)` gives `["a"]`.
  - The results of a `Try` handler are untracked: `[path(try error("x") catch .)]` raises.
  - Every other node emits untracked.
- **The state of `reduce` and `foreach` stays untracked.** jq keeps one current path per
  evaluation and restores it each time it returns to the source (`gen_reduce`, `path_intact`).
  So a state path never survives an iteration (`[[1,2]] | path(reduce (0,1) as $i (.; .[$i]))`
  raises on jq-ref). The forms jq does accept are listed in `DEVIATIONS.md`. The update body runs in the
  path variant on the untracked state, so a navigation step in it raises at once, as in jq
  (`[[1,2]] | [path(reduce (0,1) as $i (.; .[$i]) | empty)]` raises on jq-ref).
- **Validity errors:** an untracked result used as a path raises "Invalid path expression with
  result …". The check is lazy for results, so `path(1|empty)` gives nothing. A navigation step on
  an untracked value raises at once, with jq's messages "…near attempt to access element … of …"
  and "…near attempt to iterate through …", and with jq's truncated value dump. `getpath` is the
  exception (ADR-0009). The format supplies the message text and the value dump, so `query-core`
  holds neither (founding §14).
- **Updates in every shape:** the first right-hand result wins, and an empty right-hand side
  deletes the path (`{"a":0,"b":1} | .a |= empty` gives `{"b":1}`).
- **Shapes:**
  - FIXED updates run `getpath`/`setpath` on the constant chain.
  - WALK rebuilds along the steps with the Workspace path stack, and deletes apply after the walk.
    A licensed FIXED or WALK update writes owned levels in place (ADR-0022); TRACKED is never licensed.
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
