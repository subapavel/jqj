# How do other implementations execute jq?

Research note for issue #2 (Phase 0 map). Question: how jq's own VM, gojq, jaq and jackson-jq
execute a filter, track paths and do updates, handle recursion depth and numbers, and what each
lists as its differences from jq. The last section draws lessons for tickets #6 (IR shape and
analyses), #7 (execution protocol), #8 (in-place updates) and #9 (numbers).

## Sources and how claims were checked

Primary sources only: each project's own source code and its own documentation. Repositories
were shallow-cloned read-only into directories outside this repo and were only read, never built
or run.

| Tag | Repository | Revision read |
| --- | --- | --- |
| `jq` | `jqlang/jq`, tag `jq-1.8.1` | `4467af7068b1bcd7f882defff6e7ea674c5357f4` |
| `gojq` | `itchyny/gojq`, default branch | `e7c14eca0339b4a122f9b293a114e267f8699f49` |
| `jaq` | `01mf02/jaq`, default branch (jaq 3.0 line) | `c6cc7d3721ccfb212f153006c4fc90c56567540c` |
| `jjq` | `eiiches/jackson-jq`, default branch (2.x development) | `1efc4d386f6643f1d43760f2eb4da89c793f809b` |

A citation `jq src/execute.c:934` means that file and line at the revision above. Line numbers
are where the cited construct starts.

Each jq behaviour stated as fact below was run on the reference binary `/usr/local/bin/jq-ref`
(`jq-1.8.1`) in this session; those are marked **[ref]**, with the filter. Behaviours of gojq,
jaq and jackson-jq come from their source or their own documentation, not from running them.
Where a sentence is my own inference from the code and not something the source says, it is
marked *(inference)*.

## 1. jq 1.8.1: a backtracking bytecode VM

**Execution model.** The parser output is compiled to bytecode for one VM loop, `jq_next`
(`jq src/execute.c:343`). A result is produced by a top-level `RET`, which saves a fork point and
returns the value; the next call to `jq_next` resumes by backtracking into it
(`jq src/execute.c:979`). Generators are fork points: `FORK` saves the state
(`jq src/execute.c:875`), and `EACH` saves one fork point per element except the last
(`jq src/execute.c:730`). `empty` is `BACKTRACK`, which pops the newest fork point and resumes
that instruction in its "on backtrack" variant (`jq src/execute.c:807`). The state is a heap
stack that is a "directed forest of variably sized blocks" (`jq src/exec_stack.h:10`), so a
fork point keeps the frames and values it needs alive while newer frames are popped
(`stack_save` / `stack_restore`, `jq src/execute.c:214`, `:273`).

**Errors** are not a second channel: an error is stored in `jq->error` and the VM backtracks with
`raising` set; `try` is `TRY_BEGIN` … `TRY_END`, and an error raised *after* `try`'s body has
produced a value is wrapped by `TRY_END` so the `TRY_BEGIN` it passes does not catch it
(`jq src/compile.c:1013` comment, `jq src/execute.c:830`).

**label / break** are sugar over errors: `label $x | f` generates a unique label object
(`GENLABEL`, `jq src/execute.c:422`) and wraps `f` in `try … catch (if . == $label then empty else
error end)` (`gen_label`, `jq src/compile.c:1049`); `break $x` is `$*label-x | error`
(`jq src/parser.y:552`). So `try (break $b) catch .` exposes the label object **[ref]**:
`label $a | label $b | try (break $b) catch .` → `{"__jq":1}`.

**reduce / foreach** are a fork over the source plus a local variable holding the accumulator
(`gen_reduce`, `gen_foreach`, `jq src/compile.c:829`, `:846`). If the update emits several
values, `reduce` keeps the last and `foreach` emits all but continues from the last **[ref]**:
`[foreach (5, 10) as $x (1; .+$x, -.)]` → `[6,-1,9,1]`; `reduce (5,10) as $x (1; .+$x, -.)` → `1`.
Binary operators evaluate the right operand in the outer loop **[ref]**: `[(1,2)+(1,3)]` →
`[2,3,4,5]`.

**Tail calls.** After compilation `optimize_code` rewrites `CALL_JQ` to `TAIL_CALL_JQ` when the
next instruction is (a chain of jumps to) `RET` and no closure argument refers to the current
frame (`tail_call_analyze`, `jq src/execute.c:1170`). A tail call pops the caller's frame before
pushing the callee's (`jq src/execute.c:934`). The manual states the user-visible rule: tail
calls are optimised when the expression left of the recursive call has produced its last value,
in practice when it produces at most one output per input
(`jq docs/content/manual/v1.8/manual.yml:3205`).

**Recursion depth.** The VM stack is on the heap, so recursion is bounded only by memory **[ref]**:
`def f: if . < 3000000 then (.+1|f)+0 else . end; 0|f` (not a tail call) → `3000000` under a
4 GB address-space limit. Separate fixed limits: JSON parsing depth 10000
(`jq src/jv_parse.c:14`) **[ref]** (10001 nested `[` → "Exceeds depth limit for parsing"), and
printing depth 256 (`jq src/jv_print.c:20`): deeper values print as `<skipped: too deep>`, also
inside `tojson` **[ref]** (`reduce range(300) as $i (0; [.]) | tojson`).

**Paths.** Path tracking is a mode of the same VM, not a second compilation. `PATH_BEGIN` sets
`jq->path` to `[]` and `value_at_path` to the input (`jq src/execute.c:629`); `INDEX`, `EACH` and
the C builtin `getpath` append to the path (`path_append`, `_jq_path_append`,
`jq src/execute.c:237`, `:253`); fork points save and restore the path length. Values computed
inside a sub-expression (`SUBEXP_BEGIN/END`, e.g. the key in `.[expr]`) do not touch the path.
Validity is an **identity test**: before each index step and at `PATH_END`, the current value
must be `jv_identical` to `value_at_path` (`path_intact`, `jq src/execute.c:228`), else
"Invalid path expression" **[ref]**: `{a:[1]} | path(.a|reverse)` →
`Invalid path expression with result [1]`. Every function defined in jq is path-capable for
free, because it is the same bytecode. `reduce` is not **[ref]**:
`[{a:1}] | path(reduce (0,"a") as $i (.; .[$i]))` → error.

**Updates** are defined in jq. `p = v` is `reduce path(p) as $p (.; setpath($p; $v))`;
`p |= f` is `_modify`, which reduces over `path(p)`, takes the first output of `f` via
`label`/`break`, and collects paths where `f` was empty to delete them at the end
(`jq src/builtin.jq:14`, `:15`). `+=` and friends evaluate the right side once against `.`, then
call `_modify` (`gen_update`, `jq src/parser.y:262`). Observed **[ref]**: `[1,2,3] | .[] |= empty`
→ `[]`; `{a:1} | .a |= (2,3)` → `{"a":2}`; `[1,2,3] | first(.[]) -= 1` → `[0,2,3]`;
`[1,2,3] | try .[] -= 1` → `[0,1,2]`; `[1,2,3] | last(.[]) -= 1` → error;
`[] | path(.[0:])` → `[{"start":0,"end":null}]`.

**Reference counts and in-place mutation.** Every `jv` is reference counted. A write mutates in
place only when the count is one (`jvp_refcnt_unshared`, `jq src/jv.c:68`; array write
`jq src/jv.c:878`). To make that happen in `reduce`/`foreach`, the accumulator is loaded with
`LOADVN`, which moves the value out of the variable and leaves `null` (`jq src/execute.c:563`;
used in `jq src/compile.c:829`, `:846`). `_modify` uses a private syntax `$$$$v` for the same
move, with the source warning that it breaks referential transparency
(`jq src/parser.y:712` and the comment above it).

**Numbers.** A number is a `double`, or (built with decNumber) a *literal number* that keeps the
parsed decimal and computes its `double` lazily (`jvp_literal_number_new`, `jq src/jv.c:576`;
`jv_number_value`, `:699`). Two literals compare as decimals (`jvp_number_cmp`, `:770`).
Arithmetic is `double`. Observed **[ref]**:

| Filter | Output |
| --- | --- |
| `100000000000000000001` (literal), and as input | `100000000000000000001` |
| input `13911860366432393` → `.a`, `[.a][0]`, `{b:.a}`, `tostring` | kept exactly |
| same → `.+0`, `floor` | `13911860366432392` |
| `100000000000000000001 == 100000000000000000000` | `false` |
| input `[1.10,3.00,1e2,1E+2,-0,0.0]` → `.` | `[1.10,3.00,1E+2,1E+2,-0,0.0]` |
| same → `map(.+0)` | `[1.1,3,100,100,0,0]` |
| `1e17\|tostring`, `3.0\|tojson` | `"1E+17"`, `"3.0"` |
| `1e1000`, `1e1000\|.+0`, `infinite\|tostring` | `1E+1000`, `1.7976931348623157e+308`, `"1.7976931348623157e+308"` |
| `[nan,1]\|sort`, `nan < 1` | `[null,1]`, `true` |
| `0/0`, `1/0` | error (divisor is zero) |
| `[1,2,3] \| .[1.7]` | `2` |

Note that an untouched literal is printed in decNumber's canonical form, not byte for byte
(`1e2` → `1E+2`).

## 2. gojq: a backtracking VM in Go

**Execution model.** Same design family as jq: compiled to a flat code array run by one loop,
`env.Next` (`gojq execute.go:22`), with a fork stack (`pushfork`, `gojq execute.go:384`);
`Next` returns one result per call, so the public API is a pull iterator. Native generators are
`Iter` values that `opiter` resumes through a fork point (`gojq execute.go:264`; `range` is
`rangeIter`, `gojq func.go:1150`). Errors travel like jq's: `err` is set and the VM backtracks;
`try` wraps errors raised after its body (`tryEndError`, `gojq execute.go` `opforktrybegin` /
`opforktryend`). `break` is a `breakError` value matched by a label number at `opforklabel`
(`gojq execute.go:126`).

**Tail calls.** `optimizeTailRec` (`gojq compiler.go:1675`) turns a self call followed by `ret`
into a plain jump when the scope has no variables, otherwise into `opcallrec`, which reuses the
scope (`gojq execute.go:223`). The data, scope and fork stacks are Go slices on the heap
*(inference: so depth is bounded by memory, as in jq; I did not run gojq)*.

**Paths.** As in jq a mode of the same VM: `oppathbegin` / `oppathend` and an `env.paths` stack
of (path, value) pairs, pushed by index, iterate, `_index`, `_slice` and `getpath`
(`gojq execute.go:335`, `:339`). Validity is checked by `pathIntact` (`gojq execute.go:419`):
pointer and length equality for arrays and maps, value equality for scalars.

**Updates and ownership.** `=` and `|=` are hand-written bytecode, not jq definitions
(`compileAssign`, `compileModify`, `gojq compiler.go:1065`, `:1111`); `.a.b = v` with constant
indices compiles straight to one `setpath` (`gojq compiler.go:471`). Both create an `allocator`
— a set of addresses of maps and slices allocated *during this update* (`gojq func.go:1460`);
`setpath` mutates a container in place when its address is in that set, and copies otherwise.
When the update reads a value that is in the set (`getpath` before calling the update filter),
`release` clears the whole set, because the filter may store it anywhere
(`gojq func.go:1473`, `:1511`). This is an ownership marker scoped to one update operation.

**Numbers.** `int`, `*big.Int` or `float64`. Input is decoded with `UseNumber`; `parseNumber`
makes an `int` if it fits, a `float64` if the text has `.`, `e` or `E`, else a `*big.Int`
(`gojq number.go:84`) *(inference: so a decimal's original text such as `1.10` is not kept)*.
The README: "+ - * % and divisible /" keep integer precision; math functions convert to float.

**Differences gojq lists** (`gojq README.md:87`, "Difference to jq"): object key order is not
kept (no `keys_unsorted`, no `-S`; output is sorted); arbitrary-precision integer arithmetic;
string indexing `"abcde"[2]`; `@base64d` and time formatting changes; no `input_line_number`,
`$__loc__` (performance), `--ascii-output`, `--seq`; no `NaN`/`Infinity` in JSON input; regex
engine differences; keywords cannot be function names.

## 3. jaq: lazy iterators, three evaluation functions

**Execution model.** A tree interpreter: each compiled term is evaluated by a Rust function that
returns a boxed lazy iterator of results (`Id::run`, `jaq jaq-core/src/filter.rs:464`). `a | b`
is a flat-map, `a , b` a chain (same function). This is a pull model with one iterator object per
stage. Errors, `break`, `halt` and tail calls are one exception type carried *as an iterator item*
(`Exn` / `Inner`, `jaq jaq-core/src/exn.rs`), so they flow through the same channel as results.

**Paths and updates: three functions per term.** Besides `run` there is `paths`, which yields
(value, path) pairs (`jaq jaq-core/src/filter.rs:578`), and `update`, which takes the update
function and returns the updated input (`:660`). A native builtin declares all three
(`struct Native { run, paths, update }`, `:420`); `first`, `last`, `limit`, `skip` have native
`paths` (`jaq jaq-core/src/funs.rs:160`). jaq's own docs call `update` "pathless": `p |= f` is
evaluated by recursing over `p` without building path arrays (`jaq docs/advanced.dj:20`,
`### Pathless` `:137`). Consequences the docs list: multiple outputs of `f` are used where
possible (`0 | (., .) |= (., .+1)` → `0 1 1 2` in jaq; `0` in jq **[ref]**), and `try`, `label`,
`first`, `limit`, `skip`, `last` on the left of `|=` are errors in jaq (`:164`); the fallback is
`getpath(path(p)) |= u`. `reduce`/`foreach` are allowed inside `path(…)` in jaq.

**Ownership.** Arrays and objects are `Rc`; an update takes the child out of its parent with
`mem::take` (leaving the child uniquely owned) and writes through `Rc::make_mut`, which copies
only when the count is above one (`jaq jaq-json/src/lib.rs:211`, `:237`). A removed object key is
`swap_remove`d, which changes key order (comment at `:217`).

**Tail calls.** The compiler classifies each call (`CallType`: `Inline`, `Throw`, `CatchOne`,
`CatchAll`, `jaq jaq-core/src/compile.rs:74`). A tail call returns a `TailCall` exception
carrying (function, variables, input) (`jaq jaq-core/src/exn.rs:23`); the caller's `Stack`
iterator catches it and pushes a fresh iterator instead of recursing (`def_run`,
`jaq jaq-core/src/filter.rs:344`; `jaq jaq-core/src/stack.rs`). This is a trampoline.

**Recursion depth.** Non-tail recursion uses the Rust stack, and jaq says plainly it takes no
countermeasure against resource exhaustion; `jaq -n 'def f: 1+f; f'` overflows the stack, as can
reading or writing deeply nested data (`jaq README.md:159`, `### Security`).

**Numbers.** `Int(isize)`, `BigInt`, `Float(f64)`, `Dec(Rc<String>)` (`jaq jaq-json/src/num.rs:22`).
An integer literal is exact at any size; a non-integer keeps its text until arithmetic converts
it to `f64`; `+ - * %` of integers stay integer, everything else is float (`10 / 2` → `5.0`)
(`jaq docs/corelang.dj:95`). Array indices must be integers.

**Differences jaq lists** (compatibility boxes in `jaq docs/*.dj`): division by zero gives
`nan`/`±infinite` instead of an error (`corelang.dj:929`); `f + g` binds `f` in the outer loop
(`corelang.dj:765`); `reduce`/`foreach` with multi-output updates (`corelang.dj:1086`); the
update model above; `path(.[0:])` omits a missing `start`/`end` (`advanced.dj`, Compatibility box
under "Path-based"); several stdlib differences (`stdlib.dj`).

## 4. jackson-jq: push-style tree interpreter on the JVM

**Execution model.** Each node implements `void apply(context, in, ipath, Output output)`
(`jjq jackson-jq-spi/.../spi/Expression.java:29`); results are pushed with
`Output.emit(value, path)` (`jjq jackson-jq-spi/.../spi/Output.java:27`). `emit` returns
nothing, so stopping early is by exception: `break` throws `JsonQueryBreakException(name)` and
`Label` catches it when the **name** matches (`jjq …/internal/tree/Label.java:74`). `limit` and
`first` are jq definitions over `label`/`break` (`jjq …/builtins/library/CoreJqLibrary.java:241`).
Analyses on the tree include a three-valued cardinality (`ZERO`, `ONE`, `UNKNOWN`,
`jjq jackson-jq-spi/.../spi/Cardinality.java`), free variables, and constant folding
(`…/internal/compile/opt/ConstantFolder.java`).

**Tail calls.** A call in tail position becomes `ResolvedTailCall`, which writes the arguments
into the frame and asks a loop at the `def` boundary to run again. Its Javadoc states the
soundness condition: only where everything still in progress to its left emits at most one value;
the compiler drops tail position on the right of a pipe whose left side has `UNKNOWN`
cardinality (`jjq …/internal/compile/resolved/ResolvedTailCall.java`;
`…/internal/compile/Compiler.java:528`). The Javadoc adds that without this "a few hundred
iterations exhaust the stack".

**Recursion depth.** Plain Java recursion; `StackOverflowError` is caught at the top and turned
into `JsonQueryException("Stack overflow during evaluation")`
(`jjq …/internal/compile/CompiledJsonQuery.java:74`).

**Paths.** Always carried: every `apply` gets the input's path and every `emit` passes the
output's path; `UntrackedPath` stands for "not derived from the input" (Javadoc of
`Output.emit`). A path is a parent-linked chain (`jjq jackson-jq-spi/.../spi/path/Path.java`).
Variables never carry a path (compatibility item \*7).

**Updates.** `|=` runs the left side with path tracking and, for each path, rebuilds the input
through `PathOperations.mutate` with the first output of the right side
(`jjq …/tree/binaryop/assignment/UpdateAssignment.java:54`). Rebuilding an object copies all its
members into a new `LinkedHashMap` (`jjq …/internal/path/utils/PathOperations.java:307`)
*(inference: so `.[] |= f` on an n-element container does O(n²) work)*. There is no in-place
mutation; values are the provider's immutable nodes.

**Numbers.** Arithmetic reads both operands as `double`; `+` produces a `long` node when both are
integral and the result is non-zero (`jjq …/internal/json/operations/BinaryOperations.java:181`).

**Budgets.** `RuntimeLimits`: max array length, object members, string length, binary length
(`jjq jackson-jq-spi/.../spi/RuntimeLimits.java`), plus max user-defined function calls and max
outputs per expression (`jjq jackson-jq-core/.../core/RuntimeOptions.java:113`, `:138`).

**Differences jackson-jq lists** (`jjq docs/compatibility.md:133`): error wording (\*1); no `env`
(\*6); `0 / 0` is an error (\*5 — jq 1.8.1 now errors too **[ref]**); `|= empty` is an error
(\*8 — jq 1.8.1 deletes **[ref]**); variables carry no path (\*7 — jq 1.8.1 agrees **[ref]**:
`path(.foo as $a | $a)` errors); `try (break $b) catch .` always yields `{"__jq": 0}` (\*2). It
also keeps per-jq-version behaviour switches (`--jq 1.5|1.6|1.7`, version ranges on builtin
definitions, `CoreJqLibrary.java:268`).

## 5. Summary table

| | jq 1.8.1 | gojq | jaq | jackson-jq |
| --- | --- | --- | --- | --- |
| Model | backtracking VM, heap stack | backtracking VM, heap stacks | lazy iterators (pull) | tree, push `emit` |
| Stop / break | error + backtrack | `breakError` + backtrack | exception item | Java exception, by name |
| Tail calls | `TAIL_CALL_JQ` (frame pop) | jump / `opcallrec` | trampoline via exception | loop at `def`, needs left cardinality ≤ 1 |
| Deep recursion | memory-bound | memory-bound *(inference)* | Rust stack overflow | `StackOverflowError` → jq error |
| Path mode | same VM + identity check | same VM + pointer check | separate `paths` function | path on every emit |
| `\|=` | jq-defined `_modify` over paths | bytecode `_modify` over paths | separate pathless `update` | per path, rebuild copy |
| In-place | refcount = 1, `LOADVN` move | per-update allocator set | `Rc::make_mut` + `mem::take` | none |
| Numbers | double + decimal literal | int / big.Int / float64 | int / bigint / f64 / decimal text | double (long when integral) |

## Lessons for tickets 6–9

### #6 IR shape and analyses

1. **Cardinality is what makes push-style tail calls safe, not only fast.** jackson-jq allows a
   tail call only where the left side has cardinality ≤ 1 (§4), which is the manual's own rule
   (§1). The cardinality analysis (founding architecture §7) must be precise enough to feed the
   tail-call decision; "UNKNOWN" must mean "not a tail call".
2. **Path capability belongs to each IR node and each native builtin.** jq gets it for free from
   one VM; jaq has to declare `run`/`paths`/`update` for every native (§3). If hot builtins are
   native (founding architecture §3), the IR must record per intrinsic whether it is
   path-capable (`first`, `limit`, `getpath`, `select`, `recurse`, `paths`, `..`) and the path
   variant must have an implementation for each, or `limit(2; .[]) -= 1` (jq: `[0,1,3]` **[ref]**)
   breaks.
3. **`reduce` and `foreach` are not path expressions in jq** (**[ref]**), unlike jaq. The path-shape
   analysis can reject them.
4. **Desugar `=` / `|=` / `op=` to one IR node, as gojq does**, not to the prelude's `_modify`
   with `$$$$`. gojq's constant-index `=` → single `setpath` is the fixed-path fast path the
   founding architecture already plans.

### #7 Execution protocol

1. **Deep recursion is the real gap against jq.** jq ran a 3,000,000-deep non-tail recursion
   (**[ref]**); jackson-jq says a few hundred tail iterations exhaust the Java stack without its
   loop, and jaq overflows on `def f: 1+f; f`. Plan: jq's tail-call rule as a loop
   (jackson-jq's design), `StackOverflowError` → jq error (jackson-jq), and a measured fallback
   (jaq's trampoline, or a large-stack thread) — this is ticket #3's spike, and it should measure
   against jq's depth, not against "a few thousand".
2. **`break` must match a label instance, not a name.** jq makes a fresh label object per
   `label` evaluation (`GENLABEL`); jackson-jq matches by name and lists the visible symptom
   (\*2). With `emit` returning `false` (founding architecture §8), a break needs the identity of
   the label activation it targets, and `try (break $l) catch .` must give `{"__jq": <n>}`.
3. **`try` must not catch errors raised downstream of its body.** jq and gojq both wrap such
   errors (`TRY_END`, `tryEndError`). In push style the body's downstream runs *inside* the
   body's `emit`, so a naive Java `try` around the body catches them. The protocol has to mark
   errors that came back through `emit` and rethrow them past the handler.
4. **Path mode: carry the path explicitly, do not copy jq's identity test.** jq and gojq check
   that the value is still identical to the tracked one; with tape views (founding architecture
   §5) the same input node may be several Java objects, so identity is unreliable *(inference)*.
   jaq's separate `paths` variant (value, path) matches the founding plan of a second compiled
   variant, and it must still reject `path(.a|reverse)` and `path(1)` with jq's error.
5. **Budgets**: jackson-jq's limit set (array length, object members, string length, function
   calls, outputs) is a working example of founding architecture §6.6 on the JVM. jq itself has
   parse depth 10000 and print depth 256 (`<skipped: too deep>` **[ref]**); the writer must
   reproduce or declare the 256 behaviour as a deviation.

### #8 In-place updates and ownership

1. **gojq's allocator is the founding architecture's ownership marker, already working**: a set
   of containers allocated during one `=`/`|=`; mutate in place only those; clear the set when
   the update filter reads one (§2). It shows the marker can be scoped to one operation and
   cleared wholesale on the first possible escape — simple and conservative.
2. **Move out, then write.** jq's `LOADVN` (reduce/foreach accumulator) and jaq's `mem::take`
   (child taken from its parent before the update) are the same trick: leave no second reference
   so the write is unshared. In jqj this means reading the accumulator out of its local and the
   child out of its parent, not only checking a marker.
3. **Without ownership, updates go quadratic.** jackson-jq rebuilds each container per path
   (§4, inference on cost). This is the case the `.items[].price |= . * 1.2` and
   `reduce . + [$x]` benchmark scenarios exist for.
4. **Semantics to keep**: jq's `|=` uses the first output of the right side, deletes on `empty`
   (`[1,2,3] | .[] |= empty` → `[]` **[ref]**), and allows `first`, `limit`, `try` on the left;
   jaq's pathless update differs on all three, so it is not a template for semantics.

### #9 Numbers

1. **jq 1.8.1 keeps the decimal literal and compares literals as decimals**, but every
   arithmetic result is a `double` (**[ref]** table in §1). The founding plan (keep original text,
   compute in `double`) matches; comparisons of two untouched literals must be decimal
   (`100000000000000000001 == 100000000000000000000` → `false`), and `sort` must order them too.
2. **"Written from its original bytes" is not what jq does.** jq prints a literal in decNumber's
   canonical form: `1e2` → `1E+2`, `1e17|tostring` → `"1E+17"`, but `1.10`, `3.00`, `-0` keep
   their text; `1e1000` prints `1E+1000` until arithmetic clamps it to `1.7976931348623157e+308`
   (**[ref]**). Ticket #9 must specify this canonicalisation, not byte copy, or list byte copy as
   a deviation.
3. **Do not follow gojq or jaq on integers.** Both do exact big-integer arithmetic and both list
   it as a difference from jq; jq gives `13911860366432393 + 0` → `13911860366432392` (**[ref]**).
   jaq's integer-vs-float split (`10/2` → `5.0`) and division by zero (`nan`) are also
   deviations. jackson-jq's `long` fast path for integral `+` is a model for speed, provided the
   result is the same as the `double` result (it checks `-0`).
4. Edge cases to put in oracles: `nan` sorts below numbers and prints `null`, `0/0` is an error,
   `.[1.7]` indexes `1`, `infinite` prints `1.7976931348623157e+308` (**[ref]** table in §1).
