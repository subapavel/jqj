# Deviations from the reference jq

Behaviours where jqj differs from the reference jq (1.8.2) on purpose, each with its reason and the
decision that made it. A difference not listed here is a bug. Every row is pinned by an oracle case or a named test
that checks both columns. The row format is ADR-0020's; a row gains its `case` column in the
ticket that writes its pinning case. A row's `case` cell names the case and its slug, as
`` `<case id>` (`deviation <slug>`) ``; the differential runner refuses a slug no row names.

## Path expressions accept only navigation (ADR-0007, issue #6)

`path(f)`, `paths`, `|=` and the other assignments accept a path result only if it comes from the
tracked position through navigation steps and input-preserving control flow, or from the `foreach`
item variable. jq also accepts some other forms; jqj raises "Invalid path expression" for them:

| filter | jq 1.8.2 | jqj |
| --- | --- | --- |
| `[1] \| path(. as [$a] \| $a)` | `[0]` | error |
| `{"a":1} \| path(.a \| . as $v \| $v)` | `["a"]` | error |
| `{"a":true} \| path(.a \| true)` | `["a"]` | error |
| `null \| path(reduce 0 as $i (.; .))` | `[]` | error |
| `[[1],[2]] \| path(foreach (0,1) as $i (.; .; .[$i]))` | `[0]`, `[1]` | error |
| `{"a":{"a":1}} \| [path(reduce 0 as $i (.; .a) \| empty)]` | `[]` | error |

The last two keep the `reduce`/`foreach` state untracked (ADR-0010): jq restores its one current
path at every return to the source, so a state path never survives an iteration, and
`[[1,2]] | path(reduce (0,1) as $i (.; .[$i]))` raises in jq as well.

Reason: jq decides these by value identity, which jqj's views and identity-free numbers do not
have. jq's own test files contain no such case. Relaxing the rule later only turns errors into
results.

## A break is not an error (ADR-0008, issue #7)

In jq, `break $l` raises the error `{"__jq":n}`, where `n` is a per-process label counter. A `try`,
a `?` or a `?//` around it can catch it. In jqj a break is a stop signal and no catch set holds it:

| filter | jq 1.8.2 | jqj |
| --- | --- | --- |
| `[label $f \| 1, (try (break $f) catch .), 2]` | `[1,{"__jq":0},2]` | `[1]` |
| `[label $f \| 1, (break $f)?, 2]` | `[1,2]` | `[1]` |
| `[[1]] \| [first(.[] as [$a] ?// $a \| $a)]` | `[1,[1]]` | `[1]` |
| `[label $f \| 1, error({"__jq":0}), 2]` | `[1]` (a forged error breaks) | error `{"__jq":0}` |
| `label $a \| label $b \| try (break $b) catch .`, inputs `1`, `2` | `{"__jq":1}`, `{"__jq":3}` | nothing |

Reason: `first`, `limit` and `isempty` keep at most one result even when their argument contains a
`?//`, and an embedded Workspace cannot reproduce a counter that grows across inputs. jq's own test
files contain no break under `try`, `?` or `?//`.

## `last/1` has no path variant (ADR-0009, issue #7)

| filter | jq 1.8.2 | jqj |
| --- | --- | --- |
| `[1] \| path(last(.))` | `[]` | error |

Reason: jq bytecodes `last/1` and tracks its one path; jqj makes it a native, and a jq-text
definition would differ the same way. `[1,2] | path(last(.[]))` raises in both.

## Order inside a comparison cycle (ADR-0012, issue #9)

jq compares two literals as exact decimals and every other pair as doubles. So two literals with
the same double, plus a computed number equal to both, form a cycle. Inside such a cycle, the result
of `sort`, `sort_by`, `unique`, `unique_by` and `group_by` is jqj's own; `unique` and `group_by`
can also group differently (`[(1e20+0),100000000000000000001,100000000000000000000] | unique` →
`[1e+20]` on jq). `min`, `max` and their `_by` forms match jq. jq's order comes from libc `qsort`:

| filter | jq 1.8.2 |
| --- | --- |
| `[100000000000000000001,(1e20+0),100000000000000000000] \| sort` | the input unchanged |
| `[100000000000000000001,(1e20+0),100000000000000000000] \| unique` | `[100000000000000000001,100000000000000000000]` |

Reason: `==` and `<` stay identical to jq's. A total order would change
`(100000000000000000001+0) == 100000000000000000001` from true to false, and imitating `qsort` would
tie jqj to one binary. The jqj column depends on the merge order, so the sort ticket fills it in
and pins it with oracle cases.

## Size limits of the tape (ADR-0002)

jqj raises a jq error beyond these limits:
- 2^31−3 bytes per document (F-12);
- 2^28−1 bytes per string, quotes included (F-21);
- 2^24−1 bytes per number literal (jq allows up to 999 999 999 digits);
- 2^28−1 entries per container.

jq's limits are higher and of another kind: an array index up to 2^29−1 ("Array index too large"),
strings under 2 GiB, tokens up to 1 GiB, literals up to 999 999 999 characters, and no
per-document limit. Reason: one `long` per value, as recorded in ADR-0002.

## Streaming, when a host enables it (ADR-0007, issue #6)

Streaming is off by default; in that mode jqj behaves like jq. When a host enables streaming for an
eligible filter (`<fixed path> | .[] | rest`):

- **Results before a late error are kept**: results are emitted before a later syntax error is
  seen. jq prints none.
- **Duplicate keys**: a duplicate of a key on the streamed path is not seen. On
  `{"items":[1],"items":[2]}`, jq gives `2`.

## A raw NUL byte outside a string is an error (ADR-0015, issue #11)

| case | filter | input | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `-c .` | `1\x002` | `1` | "Invalid character" error at the NUL |
| none yet | `-c .` | `[1\x00x,2]` | `[1,2]` | error at the NUL |
| `json/n_multidigit_number_then_00` (`deviation nul`) | `-c .` | JSONTestSuite `n_multidigit_number_then_00.json` (`123\x00`) | accepted: `123` | rejected |

Reason: bytes would hide behind a NUL in a host's message; jq's master branch already rejects a NUL
there (`classify` returns INVALID) with the text "Invalid character" (master source read, not
run); jqj uses that text, so the row can go once the reference moves past 1.8.2. The reader
ticket's oracle cases pin line and column.

## Regex limit by time, not by retries (ADR-0017, issue #13)

jq stops a match after Oniguruma's retry limit with the catchable error "Regex failure:
retry-limit-in-match over" (`("a"*24+"b") | test("^(a+)+$")`). joni has no retry counter, so jqj
raises the same error when a match exceeds `regexMatchTime` (1 s). The cut-off depends on the
machine, so some patterns succeed in jqj where jq errors, and the reverse.

## Recursion depth on the command line (ADR-0017, issue #13)

jq-ref completes 10⁷ levels of `def f: if .==0 then 0 else (.-1|f)+1 end`. jqj's command line
keeps the recursion-depth budget (4 194 304 live activations, at least 10⁶ levels) and ends deeper
recursion with a termination. Reason: recursion depth bounded by memory, ADR-0011.

## Parse depth (ADR-0017, issue #81)

jq's parser stops when its Bison stack is full (YYMAXDEPTH, 10 000 states) with `memory exhausted`, rc 3. jqj's
stops at 512 nesting levels, with jq's `memory exhausted` block at the token that opens level 513, rc 3. Both
sides are recorded on 1.8.2; jqj's boundary is pinned by jq-core ParseDepthTest:

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `depth/paren-9994` (`deviation parse-depth`) | 9994 parentheses around `1` | `1` | rc 3, `memory exhausted` at column 129 |
| `depth/paren-9995` (`deviation parse-depth`) | 9995 parentheses | rc 3, `memory exhausted` at column 9997 | rc 3, column 129 |
| `depth/array-9994` (`deviation parse-depth`) | 9994 brackets around `1` | the nested arrays | rc 3, column 129 |
| `depth/array-9995` (`deviation parse-depth`) | 9995 brackets | rc 3, column 9997 | rc 3, column 129 |
| `depth/object-3331` (`deviation parse-depth`) | 3331 objects `{"a": …}` around `1` | the nested objects | rc 3, column 641 |
| `depth/object-3332` (`deviation parse-depth`) | 3332 objects | rc 3, column 16658 | rc 3, column 641 |
| `depth/pipe-10000` (`deviation parse-depth`) | `1\|1\|…\|1`, 10 000 pipes | rc 3, column 9997 | rc 3, column 1021 |

jqj accepts 127 parentheses, brackets or objects and 509 pipes, `-` or `try`. Reason: jqj's parser is recursive
descent (ADR-0032), so its depth is Java stack, and the engine starts no thread (ADR-0017). 9994 parentheses need
about 20 MB of stack; 512 levels fit a 512 KiB stack (ADR-0017's `stackBytes`) with about a third to spare.

## Huge constant keys (ADR-0035, issue #65)

jq folds a computed object key whole at parse time, so a key that folds to an array or an object is its compile
error, rc 3, however much the fold builds. jqj folds each node of a filter at most once, and every fold of one
compile, the key checks and the literals a fold builds included, shares one budget of 1 MiB of bytes built and 2^20
steps (ADR-0035, bd34167, 31aca88). A key the budget does not hold is left to the run, which raises the same text
with rc 5 when it reaches the key, and gives the filter's other outputs, rc 0, when it never does. When another key
of the same filter already failed its check, the compile still fails with rc 3, one error short. Both sides are
recorded on 1.8.2. The bound's value is pinned by the edge pair `constant-key-cap/check-edge-under` (1048540 bytes
built, rc 3 as in jq) and `constant-key-cap/check-edge-over` (1048589 bytes, 13 past the bound, the first row below);
jq-core HugeConstantKeyTest pins that a compile past the bound allocates no gigabyte, and the compile budget's bytes,
steps and literal charges:

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `constant-key-cap/check-edge-over` (`deviation huge-constant-keys`) | `{(("x"*21395)/"x"):1}` | rc 3, `Cannot use array (["","","","","","","","",...]) as object key` at column 3 | rc 5, the same text at run time |
| `constant-key-cap/huge-split` (`deviation huge-constant-keys`) | `{(("x"*2000000)/"x"):1}` | rc 3, the same text | rc 5, the same text at run time |
| `constant-key-cap/check-whole-fold` (`deviation huge-constant-keys`) | `{([("x"*1000000),("x"*1000000)]):1}` | rc 3, `Cannot use array (["xxxxxxxxxxxxxxxxxxxxxxx...]) as object key` | rc 5, the same text at run time |
| `constant-key-cap/check-after-the-compile-budget` (`deviation huge-constant-keys`) | `{(("x"*21394)/"x"):1},{(("x"*5000)/"x"):1}` | rc 3, `Cannot use array` at columns 3 and 25, `jq: 2 compile errors` | rc 3, the error at column 3 only, `jq: 1 compile error` |
| `constant-key-cap/unreached-huge-key` (`deviation huge-constant-keys`) | `1, (empty \| {(("x"*2000000)/"x"):1})` | rc 3, `Cannot use array (["","","","","","","","",...]) as object key` at column 15 | rc 0, `1` |

Reason: an untrusted filter such as `{(("x"*1e9)/"x"):1}` would otherwise make the compile allocate gigabytes, where
no budget applies (ADR-0035). A key below the bound keeps jq's rc 3 (`constant-key-cap/split`).

## `$JQ_BUILD_CONFIGURATION` (ADR-0017, issue #13)

jq prints its configure string; jqj prints its own build string.

## Syntax-error text (ADR-0020, issue #15)

| filter | jq 1.8.2 (stderr) | jqj |
| --- | --- | --- |
| `. as [] \| null` | `jq: error: syntax error, unexpected ']', expecting BINDING or '[' or '{' at <top-level>, line 1, column 7:` … `jq: 1 compile error`, rc 3 | jqj's own syntax-error text, rc 3 |

Reason: jq's syntax-error text comes from Bison's LALR tables (expecting lists that depend on the
parser state, several errors after recovery); jqj's Pratt parser (founding §3) writes its own. A
case whose recording says `syntax error` compares rc and stdout only.

jqj's syntax block is jq's with its expecting list cut (ADR-0032). Its lexical blocks and the
blocks of the two recovery productions it builds match jq's exactly: `'.' error` and `'.' IDENT
error` (`try .["field"] instead of .field for unusually named fields`), and `error ':' DictExpr`
(`May need parentheses around object key expression`). Where jq recovers by a production jqj does
not build yet, jqj stops, and jq's blocks after that point are missing. These are the messages
`Possibly unterminated 'if' statement` (`if . then 1`), `Possibly unterminated 'try' statement`
(`try 1 catch ]`), `break requires a label to break to` (`break 1`) and an object pattern's `May
need parentheses around object key expression` (`. as {1+2: $x} | 1`). They are also the silent
`'(' error ')'`, `'[' error ']'`, `Term '[' error ']'` and `'{' error '}'`, after which jq goes on
and reports later errors (`(1 2) | 3 | 4 5`). jqj's blocks are then a prefix of jq's.
ParserEdgesTest pins jqj's blocks for each example.

## `-h`, `-V` and `--build-configuration` print jqj's own text (ADR-0023, issue #17)

| flag | jq 1.8.2 | jqj |
| --- | --- | --- |
| `-h` / `--help` | jq's usage text | jqj's usage text (written by spec 7) |
| `-V` / `--version` | `jq-1.8.2` | jqj's version (written by spec 7) |
| `--build-configuration` | jq's build string, as `$JQ_BUILD_CONFIGURATION` | jqj's build string |

Reason: these flags describe the program, not a filter's result; jqj is not jq. jq's rc-2 usage
lines (`jq: Unknown option …`, `Use jq --help for help with command-line options,`) stay
byte-identical. shtest's cases for these flags become deviation cases.

## Long comma chains (ADR-0034, issue #63)

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `depth/comma-10922` (`deviation long-comma-chains`) | `1,1,…,1`, 10 922 commas, on `null` | rc 3, `jq: error: function compiled to 65536 bytes which is too long` | the 10 923 ones, rc 0 |

jq-ref compiles 10 921 commas (`depth/comma-10921`, matched) and rejects one more with its own bytecode limit: a
function's code must fit 65 536 bytes. jqj compiles to an IR and an interpreter tree with no such limit, and a long
chain is bounded at run time by the step budget instead (one tick per right branch, F-32; jq-core
CommaChainTest pins chains of 10⁴ and 10⁵).

## Array length limit (ADR-0035, issue #64)

jq 1.8.2 raises "Array index too large" when an array grows past an index of 2^29−1, as `jv_array_append` does
when it collects one more result (`jv_array_set`, src/jv.c:1020). jqj ends a collection that would pass
`Integer.MAX_VALUE − 8` elements with exit 5 and "Bytes-built budget exceeded". It does so even when the bytes-built
budget is unlimited, as on the command line. Reason: a JVM array holds no more elements than that. Also, a collector
charges each element to the bytes-built budget before storing it (ADR-0035), so a collection that large exhausts any
real budget first. A termination of its own would add a public cause for a case no run can reach. query-core's tests
of `CollectOp.grown` pin jqj's side.
