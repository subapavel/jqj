# What exactly must we match? (reference jq, language, builtins, CLI, test files, licences)

Ticket #1 of the Phase 0 map. Researched 2026-11-10 against jq's repository at the tags `jq-1.8.1`
(commit `4467af7`) and `jq-1.8.2` (commit `34f7186`), cloned read-only, and the binary
`/usr/local/bin/jq-ref`. Links point at the `jq-1.8.2` tag unless they say otherwise. Facts marked
**[ref]** were observed by running jq-ref (jq 1.8.1). Facts marked **[src]** were read in the
source. Text from the manual is paraphrased here, not copied.

## 1. Reference jq version: pin 1.8.2 (this differs from setup.sh)

- The 1.8.x tags are `jq-1.8.0`, `jq-1.8.1`, `jq-1.8.2rc1` and `jq-1.8.2`. There is no later 1.8
  tag (`git ls-remote --tags https://github.com/jqlang/jq`). The `jq-1.8.2` release has assets: `jq-linux-amd64` resolves with HTTP 200, and its
  `sha256sum.txt` lists `jq-linux-amd64` as
  `b1c22172dd303f3be49e935aa56aa48a8b7a46e0bc838b4997d3bb451495870f`. Commit dates: the 1.8.1
  tagged commit is from 2025-07-01, the 1.8.2 one from 2026-06-20. The release-page publish
  date was not observed (the GitHub API for jqlang/jq is blocked from this session).
- **Recommendation: reference jq = 1.8.2.** It is the newest 1.8.x release, and the founding
  architecture fixes the 1.8 line and leaves the exact pin to Phase 0
  (`docs/FOUNDING-ARCHITECTURE.md` §12, lines 200-205). 1.8.2 changes behaviour that the cases observe:
  - The wording of runtime errors changed. A 1.8.1 index error reads
    `Cannot index array with string "c"` **[ref]**; the 1.8.2 `tests/jq.test` expects
    `Cannot index array with string ("c")`, `Cannot index number with number (0)`, and longer
    truncation with a closing `..."` (diff of `tests/jq.test` 1.8.1→1.8.2).
  - `rtrimstr("")` outputs `""` in 1.8.1 (`["a","xx",""] | [.[]|rtrimstr("")]` → `["","",""]`
    **[ref]**). 1.8.2 fixes this ([NEWS.md](https://github.com/jqlang/jq/blob/jq-1.8.2/NEWS.md)
    "Changes to existing functions"; the `builtin.jq` definition changed at line 79).
  - 1.8.2 also fixes `@uri`/`@urid` multi-byte handling, `del(.[nan])`, `tonumber`/`toboolean`
    on strings with NUL, multi-byte `-R` input and the modulo UB. It raises the print depth from 256
    to 10000 and fixes 16 CVEs plus 2 GHSAs (all in NEWS.md under 1.8.2).
  - Running the 1.8.2 test files through jq-ref 1.8.1 **[ref]**: `base64.test` passes 9 of 10,
    `uri.test` passes 18 of 20, and `jq.test` aborts with `cannot allocate memory` while the
    `?//` cases run (case #197, under a 2 GB `ulimit -v`; without that limit the run was killed for
    lack of memory). Before the abort, 3 cases fail: the `tonumber`/`toboolean` NUL cases. With
    1.8.1 as the reference, the oracle and the 1.8.2 test files would therefore disagree.
- **Mismatch:** `deploy/claude-cloud/setup.sh` line 121 has `JQ_REF_VERSION=1.8.1`. The installed
  jq-ref reports `jq-1.8.1`, and its sha256 `020468de…2a0d` equals the official 1.8.1
  `sha256sum.txt` entry for `jq-linux-amd64` **[ref]**. Changing the pin to 1.8.2 is the
  architect's call. The script does not check the sha256 today.
- jq-ref build **[ref]** (`--build-configuration`): static, `--with-oniguruma=builtin`;
  `have_decnum` = `true`.

## 2. Language constructs (lexer.l, parser.y)

Source: [src/lexer.l](https://github.com/jqlang/jq/blob/jq-1.8.2/src/lexer.l) lines 40-142 and
[src/parser.y](https://github.com/jqlang/jq/blob/jq-1.8.2/src/parser.y) lines 100-960 **[src]**.
Every row below was run through jq-ref **[ref]**, except module syntax and `$$$$`.

- **Program shape:** an optional `module <obj>;`, then `import "p" as name;` (code), `import "p" as
  $name;` (data, `.json`) or `include "p";`, each with optional metadata. After those come either a
  filter or only `def`s (a library). The search path defaults to `~/.jq`, `$ORIGIN/../lib/jq` and
  `$ORIGIN/../lib` (`main.c` lines 573-575). The lookup rules are in `linker.c` lines 186-204.
- **Precedence**, lowest first (`parser.y` lines 100-114): `def` scope, then `|` (right), `,`
  (left), `//` (right), then non-associative `=`, `|=`, `+=`, `-=`, `*=`, `/=`, `%=`, `//=`. Then
  `or`, `and`, the non-associative comparisons `== != < > <= >=`, then `+ -` and `* / %` (left).
  Postfix `?`, `.`, `[` and field access bind tightest, and `try`/`catch` have their own precedence.
- **Terms:** `.`, `..`, `.foo`, `."str"`, `.[q]`, `.[]`, `.[a:b]`/`.[a:]`/`.[:b]`. Each of these
  also chains after a term, with or without a dot (`.a.[0]`, `.a[0]`), and each takes an optional
  `?`. Then: number, string, `@fmt` and `@fmt "…\(…)…"`, unary `-`, `( q )`, `[ q ]`, `[]`,
  `{…}` (keys may be ident, keyword, string, interpolated string, `$var`, `$__loc__` or `(q)`;
  `{a}` and `{$a}` are shorthand). Also `$name`, `$__loc__` (→ `{file,line}`), `name` and
  `name(a; b)`, and `break $label`.
- **Control:** `if … then … (elif …)* (else …)? end` (the `else` may be left out);
  `try e (catch e)?`; `reduce E as P (init; update)`; `foreach E as P (init; update)` and
  `foreach E as P (init; update; extract)`; `label $l | q`; `E as P | q`; the destructuring
  alternatives `E as P1 ?// P2 ?// … | q`. Patterns are `$v`, `[P, …]` and
  `{key: P, $v, $v: P, "str": P, (q): P}`.
- **Definitions:** `def f: q;` and `def f(a; $b): q;`. A `$b` parameter binds the value and also
  defines `b` as a filter. Definitions nest and have lexical scope. Arity is part of the name.
- **Strings:** JSON escapes, `\(…)` interpolation and `@fmt` strings. There are 10 formats:
  `text json html uri urid csv tsv sh base64 base64d` (`builtin.c`, `f_format`). `@base32` is not
  supported **[ref]**.
- **Comments:** `#` to end of line. A backslash before the newline carries the comment onto the
  next line (`lexer.l` lines 40-45; observed **[ref]**).
- **Special variables:** `$ENV` (`compile.c` line 1133), `$__loc__`, `$ARGS` (`{positional,named}`,
  plus every `--arg`/`--argjson`/`--slurpfile`/`--rawfile` name, `main.c` lines 625-638).
- **Private:** `$$$$v` (LOADVN) is used only inside `builtin.jq`'s `_modify`. The parser
  comment forbids it for users (`parser.y` lines 688-700). It is not a language feature to match.

## 3. Builtin inventory

Method: a script read `builtin.jq` (top-level `def`s), `function_list[]` in `builtin.c` (lines
1974-2052), `BINOPS` in `builtin.h`, `libm.h` and the bytecoded builtins (`builtin.c` lines
2081-2130). It classified every `name/arity`. Checked against `jq-ref -n 'builtins'`: **226**
public names **[ref]**, every one classified, none missing. `builtins` leaves out names starting
with `_` (`compile.c` line 500). Both tags give an identical inventory. Only two jq-written
definitions changed between them: `from_entries` and `rtrimstr`.

| Defined in | Public | Internal (`_…`) |
|---|---:|---:|
| jq (`src/builtin.jq`, 106 top-level defs) | 103 | 3 |
| C, `function_list[]` | 56 | 8 |
| C, libm wrappers (`libm.h`) | 61 | 0 |
| C, operator functions (`BINOPS`) | 0 | 11 |
| Bytecode built in `builtin.c` | 5 | 0 |
| Generated list (`builtins/0`) | 1 | 0 |
| **Total** | **226** | **22** |

- **jq-written, public (103):** IN/1 IN/2 INDEX/1 INDEX/2 JOIN/2 JOIN/3 JOIN/4 abs/0 add/0 add/1
  all/0 all/1 all/2 any/0 any/1 any/2 arrays/0 ascii_downcase/0 ascii_upcase/0 booleans/0 capture/1
  capture/2 combinations/0 combinations/1 debug/1 del/1 error/1 finites/0 first/0 first/1 flatten/0
  flatten/1 from_entries/0 fromdate/0 fromdateiso8601/0 fromstream/1 group_by/1 gsub/2 gsub/3
  halt_error/0 in/1 index/1 indices/1 inputs/0 inside/1 isempty/1 isfinite/0 iterables/0 join/1
  last/0 limit/2 ltrimstr/1 map/1 map_values/1 match/1 match/2 max_by/1 min_by/1 normals/0 nth/1
  nth/2 nulls/0 numbers/0 objects/0 paths/0 paths/1 pick/1 range/1 range/3 recurse/0 recurse/1
  recurse/2 repeat/1 reverse/0 rindex/1 rtrimstr/1 scalars/0 scan/1 scan/2 select/1 skip/2
  sort_by/1 split/2 splits/1 splits/2 strings/0 sub/2 sub/3 test/1 test/2 to_entries/0 todate/0
  todateiso8601/0 tostream/0 transpose/0 trimstr/1 truncate_stream/1 unique_by/1 until/2 values/0
  walk/1 while/2 with_entries/1. Internal: `_assign/2` (the `=` operator), `_modify/2` (`|=`),
  `_flatten/1`.
- **C, public (56):** bsearch/1 contains/1 debug/0 delpaths/1 endswith/1 env/0 error/0 explode/0
  format/1 fromjson/0 get_jq_origin/0 get_prog_origin/0 get_search_list/0 getpath/1 gmtime/0 halt/0
  halt_error/1 has/1 have_decnum/0 have_literal_numbers/0 implode/0 infinite/0 input/0
  input_filename/0 input_line_number/0 isinfinite/0 isnan/0 isnormal/0 keys/0 keys_unsorted/0
  length/0 localtime/0 ltrim/0 max/0 min/0 mktime/0 modulemeta/0 nan/0 now/0 rtrim/0 setpath/2
  sort/0 split/1 startswith/1 stderr/0 strflocaltime/1 strftime/1 strptime/1 toboolean/0 tojson/0
  tonumber/0 tostring/0 trim/0 type/0 unique/0 utf8bytelength/0. Internal: `_negate/0`,
  `_strindices/1`, `_match_impl/3`, `_sort_by_impl/1`, `_group_by_impl/1`, `_unique_by_impl/1`,
  `_min_by_impl/1`, `_max_by_impl/1`. The operator functions are `_plus _minus _multiply _divide
  _mod _equal _notequal _less _lesseq _greater _greatereq`, all /2.
- **libm (61):** 0-arity on `.`: acos acosh asin asinh atan atanh cbrt ceil cos cosh erf erfc exp
  exp10 exp2 expm1 fabs floor gamma j0 j1 lgamma log log10 log1p log2 logb nearbyint rint round
  significand sin sinh sqrt tan tanh tgamma trunc y0 y1. `frexp`, `modf` and `lgamma_r` return
  `[value, part]`. 2-arity: atan2 copysign drem fdim fmax fmin fmod hypot jn ldexp nextafter
  nexttoward pow remainder scalb scalbln yn. 3-arity: fma. A libm function missing at build time
  raises "…not found at build time" (`builtin.c` lines 150-227). In jq-ref all 61 return a
  value **[ref]**.
- **Bytecode (5):** `empty/0`, `not/0`, `path/1`, `last/1`, `range/2`. Plus `builtins/0`.
- Calls such as `error/0` (C) and `error/1` (jq) share a name but have different arities. No
  `name/arity` is defined in both places.

## 4. CLI flags ([src/main.c](https://github.com/jqlang/jq/blob/jq-1.8.2/src/main.c) lines 49-121, 381-519)

The documented flags (the `--help` text **[ref]**) are listed below. Short flags combine (`-nr`),
and `--` ends option parsing.
`-n --null-input`, `-R --raw-input`, `-s --slurp`, `-c --compact-output`, `-r --raw-output`,
`--raw-output0`, `-j --join-output`, `-a --ascii-output`, `-S --sort-keys`, `-C --color-output`,
`-M --monochrome-output`, `--tab`, `--indent n` (-1..7), `--unbuffered`, `--stream`,
`--stream-errors`, `--seq`, `-f --from-file`, `-L --library-path dir` (also `-Ldir`),
`--arg n v`, `--argjson n j`, `--slurpfile n f`, `--rawfile n f`, `--args`, `--jsonargs`,
`-e --exit-status`, `-V --version`, `--build-configuration`, `-h --help`. These are parsed but
not in `--help`: `-b --binary` (Windows only), `--debug-dump-disasm`, `--debug-trace`,
`--debug-trace=all` and `--run-tests` (the test-file runner).
Environment variables: `JQ_COLORS`, `NO_COLOR` (`main.c` lines 551-569).
Exit codes (`main.c` lines 165-173): 0 = ok; 1 = with `-e`, last result false or null; 2 = usage or
system error; 3 = compile error; 4 = with `-e`, no result; 5 = runtime error. `halt_error` sets
its own code. Between 1.8.1 and 1.8.2 `main.c` changed only a help-text typo and the NUL check for
`-f` files (diff).

## 5. jq's test files (`tests/`, counted, not estimated)

**Format** ([src/jq_test.c](https://github.com/jqlang/jq/blob/jq-1.8.2/src/jq_test.c) lines
63-258):
- A case is a filter line, then one input line, then zero or more expected result lines. A blank
  line or a `#` line ends it.
- A line `%%FAIL` makes the next case one whose filter must *fail to compile*. Each following
  line is a prefix that the `jq: error…` message must match. `%%FAIL IGNORE MSG` skips the message
  check.
- Expected results are compared with `jv_equal`, which compares values, not text. Too few or too
  many results fail the case. A runtime error counts as too few results unless the filter catches
  it.
- One `jq_state` is reused for every case in a file. The `-L` modules directory comes from
  `tests/modules`.

The counts below come from a parser that follows those rules (script in scratch, not committed).
For 1.8.1 they equal jq-ref's own `--run-tests` totals (`N of N tests passed`) **[ref]**.

| File | 1.8.1 cases | 1.8.2 cases | of which `%%FAIL` (1.8.2) | Run by |
|---|---:|---:|---:|---|
| `jq.test` | 522 | **550** | 19 (18 check the message) | `tests/jqtest` |
| `man.test` | 231 | 231 | 0 | `tests/mantest` (sets `PAGER=less`) |
| `manonig.test` | 19 | 19 | 0 | `tests/manonigtest` (regex) |
| `onig.test` | 47 | 47 | 0 | `tests/onigtest` (regex) |
| `base64.test` | 10 | 10 | 0 | `tests/base64test` |
| `uri.test` | 7 | **20** | 0 | `tests/uritest` |
| `optional.test` | 2 | 2 | 0 | `tests/optionaltest` |
| **Total** | 838 | **879** | 19 | |

- Under jq-ref 1.8.1 every 1.8.1 file passes in full **[ref]**. `man.test` passes 231 of 231
  only with `PAGER=less` set; without it, 229 (the `$ENV.PAGER` and `env.PAGER` cases).
- `man.test` and `manonig.test` are *generated* from the manual's examples by
  `docs/build_mantests.py`. The split: programs that call regex builtins go to `manonig`.
  `docs/content/manual/v1.8/manual.yml` has 250 `program:` examples = 231 + 19.
- Shell-driven, not case files: `shtest` (936 lines, the CLI flags and exit codes), `utf8test`,
  `jq-f-test.sh`, the `*.jq` fixtures, `tests/modules/` (1.8.2 adds `cycle_*.jq`) and
  `tests/torture/`. The `jq_fuzz_*` files are fuzz harnesses.
- Groups in 1.8.2 `jq.test` that need a host or environment set up. These are counted by matching
  the program line, so they are approximate: 18 cases use modules
  (`import`/`include`/`modulemeta`/`get_search_list`), 14 use time functions, 9 use
  `have_decnum`/`have_literal_numbers` and 3 use `input`/`inputs`/`$__loc__`/`input_filename`.
  This is input for ticket 17.

## 6. Licences of what we might copy

- **`src/builtin.jq` and `tests/*.test`, `tests/*` fixtures:** jq's code licence, MIT.
  `COPYING` lines 1-20 read "jq is copyright (C) 2012 Stephen Dolan" followed by the MIT text
  ([COPYING](https://github.com/jqlang/jq/blob/jq-1.8.2/COPYING)). MIT is on our allowed list.
  Copies keep that notice and are listed in `NOTICE`.
- **The manual and everything under `docs/`:** Creative Commons **CC BY 3.0** (`COPYING` lines
  24-28; the site footer `docs/templates/shared/_footer.html.j2` says the same). Our rule is not to
  copy manual text anyway.
- `COPYING` also carries notices for third-party C code jqj will not copy: dtoa/g_fmt (lines 36-69),
  decNumber under the ICU licence (lines 73-77), Heimdal (line 114) and NetBSD's `strptime`
  (line 148).

## 7. Open ambiguities

1. **The version pin:** 1.8.2 (recommended) or 1.8.1 (installed). Choosing 1.8.2 means changing
   `JQ_REF_VERSION` in `setup.sh`, and preferably checking the sha256 above. Until then,
   differential runs use 1.8.1.
2. **`man.test`/`manonig.test` licence:** they live in `tests/`, outside `docs/`, so `COPYING`
   puts them under MIT. But their content is generated from the CC BY 3.0 manual examples. Copying
   them may conflict with "do not copy manual text". Options: copy them with both notices, or
   regenerate equivalent cases from the reference jq rather than from the manual. This needs an
   owner decision or an ADR.
3. **Error message text:** jq's cases compare `%%FAIL` compile messages by prefix, and
   `try … catch .` cases compare runtime messages exactly. Decide whether matching jq's message
   wording is in scope or is a recorded deviation. It changed between 1.8.1 and 1.8.2, see §1.
4. **Build-dependent builtins:** decNumber (`have_decnum` = true in jq-ref) and libm availability
   change results. jqj needs to pick one profile (likely jq-ref's: decNumber on, all 61 libm).
   Some libm functions (`gamma`, `drem`, `significand`, `exp10`, `j0`…) have no direct
   `java.lang.Math` equivalent.
5. **Environment-dependent cases:** `$ENV.PAGER` (needs `PAGER=less`), time zone and locale for
   `localtime`/`strflocaltime`/`mktime`, and the module search path. Each needs a fixed oracle
   environment or a deviation entry.
6. **Not observed:** the publish date of the GitHub release page for 1.8.2. jq-ref 1.8.2 was not
   run: the binary was not downloaded or executed in this research.
