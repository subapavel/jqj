# Deviations from the reference jq

Behaviours where jqj differs from the reference jq (1.8.2) on purpose, each with its reason and the
decision that made it. A difference not listed here is a bug. Every row is pinned by an oracle case that checks both
columns.

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
- 4 GiB per document;
- 256 MiB per string;
- 16 MiB per number literal (jq allows up to 999 999 999 digits);
- 2^28 entries per container.

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
