# Spec 5 "JSON in full" — grilling record

Input for `/to-spec`. Under the owner's delegation of 2026-11-14 (until Monday 2026-11-16 morning,
`agent/HANDOFF.md`, comment on #56), the `grilling-adviser` (Fable) answers for the owner. Drafts:
designer (round 1). jq behaviour: jq-ref 1.8.2 probes by the architect (P1–P7) and the adviser, and
jq 1.8.2's `jv_parse.c`, `main.c`, `util.c` and `builtin.jq` at the tag; master is read, not run.

To show the owner on Monday (adviser's list): these change owner-accepted text — ADR-0016 (question
1's read method, `-n` out of `InputOptions`, question 9's withers); ADR-0018 and the glossary (the
conservative compile detaches at every read, question 7); ADR-0002/0012 (NONFINITE never written,
question 5); ADR-0015 (A1 copies a jq quirk that loses a byte, a DEVIATIONS row is the other way;
A2); ADR-0023 (the split, A3's census rows); host streaming saves memory only on an array target
(question 2). No owner-only matter arose: each newly translated jq file is MIT, under `NOTICE`.

## Round 1 (grilling-adviser for the owner, 2026-11-14)

1. **The main loop pulls with `JsonDoc Workspace.read(JsonInputs)`** (a), null at the end of the
   input stream. The same `JsonInputs` in `RunOptions` feeds `input` (`[., input]` on `[1] [2] [3]`
   → `[[1],[2]]`, then "jq: error (at <stdin>:0): break", rc 5). A `JqSyntaxException` leaves it
   resumable; the command line decides as `main.c` does: "jq: parse error: …" and stop, rc 5, or
   under `--seq` "jq: ignoring parse error: …", read on, rc unchanged. `-n` leaves `InputOptions`:
   the command line runs once on null (P5: `-ns .` → `null`, `-ns [inputs]` → `[[1,2]]`).
   `JsonInputs` exports the current file name and line for `(at <file>:N)`.
2. **Host streaming hands out element documents on one reused tape** (a); outside the target the
   reader only validates (Spike B: 22–24 ms against 33–35 ms per 10 MB). Conditions: a target that
   is no array, or a missing path, falls back to a whole read and `run` (`{"a":{"x":1}} | .a[]` →
   `1`; `{} | .items[]` → "Cannot iterate over null (null)"); depth, line and column continue from
   the enclosing document; when the sink stops, the rest is not read (a DEVIATIONS line);
   eligibility is a syntactic test over spec 3's Operation facts, unproven → `needsWholeDocument`.
3. **One translated file, `JvParser`, with a replay mode and a stream mode** (a). `stream_token` is
   a second state machine with its own texts: of 20 malformed inputs probed, 8 give another text
   under `--stream` (`{"a":}` → "Unmatched '}'" against "Missing value in key:value pair").
   `--stream` never runs the fast reader and has no depth limit (20 000 open brackets end in
   "Unfinished JSON term at EOF"); events before an error are emitted (P5: `[1,[2` → `[[0],1]`).
4. **The error replay starts at the first byte after the last emitted value, in a fresh parser
   state** with jq's line, column, BOM and seq state. A replay that accepts what the fast reader
   rejected throws `IllegalStateException` (a): a reader bug must not look like a malformed
   document. Added: the fast reader emits a top-level number or literal only if the next byte is
   not `]`, `}`, `,`, `:` or NUL (`1]` prints nothing, `1 ]` prints `1`), and
   `fastVerdictEqualsReplay` also compares the count of values emitted before the error.
5. **Lenient tokens are checked at read and decoded at access** (a). A token ends at jq's class
   boundary (P3: `1true` → "Invalid numeric literal at EOF at line 1, column 5"); the fast path
   stays strict; a lenient token clears CANONICAL and FITS_LONG (P3: `+1` → `1`, `2.e3` → `2E+3`,
   `nan` → `null`). NONFINITE becomes a reserved bit: writing it would make the fast path weigh
   every exponent (`1e1000000000` is strict; P3: `1.7976931348623157e+308`).
6. **Read blocks are virtual** (a): the reader takes what the channel gives, up to 64 KiB a read,
   never waits for a full buffer before it parses (jq-ref prints the first of two lines sent a
   second apart before the second arrives), and computes block ends only for `(at <file>:N)`,
   `input_line_number`, `-R` (A1) and the drop after a caught `input` error. Required beside the
   draft's case: `1 x] 2 3\n4\n` under `[limit(5; repeat(try input catch .))]` →
   `[1,"Invalid numeric literal at line 1, column 4",4,"break","break"]`.
7. **Holds: 5a detaches every `input` document at its read; 5b adds the hold analysis to the
   optimised compile** (a, reshaped).
   - A hold is every place a value stays live across a node that may read input, that node's own
     `.` included. The draft's list missed the pipe: `input | [., input]` → `[1,2]`. Also required
     (stdin `1 2 3`): `def f(g): [g, g]; input | f(., input)` → `[1,2,1,3]`.
   - [2b, 3] "May read input" is a per-group fact computed as 2b computes may-break-out, from the
     `reads` fact spec 3 puts on Operation. No IR kind is added (one that proves needed ships its
     heap form and path variant: 2b question 4, spec 3 A2).
   - The conservative compile keeps detach-at-read for good: under "detach at every hold" both
     compiles share one hold list, and the compile-mode oracle cannot see a missed hold.
8. **The NUL line is master's one change to `classify`** (a): NUL is class INVALID, nothing else
   differs; jq 1.8.2's `scan` already holds `case INVALID: return "Invalid character"`, unreached.
   The pending token's value is dropped, as for `1]`: `123\x00` → empty stdout, "jq: parse error:
   Invalid character at line 1, column 4", rc 5 (jq 1.8.2, P1: `123`, rc 0). `fromjson`'s text is
   cut at the NUL as jq's `%.*s` cuts it (`"1\u0000]"` → "… (while parsing '1')").
9. **Writer options are withers on `OutputOptions`** (a, reshaped): `indent(int)` for 0..7,
   `tab()`, `sortKeys()`, `ascii()`, `raw(RawMode {RAW, JOIN, NUL})`, `seq()`, `color()`,
   `color(String jqColors)`; `-e` and `--unbuffered` stay in jq-cli. No `indent(-1)`: the command
   line maps it to `tab()`, and folds `-c`, `--tab` and `--indent` in argument order, the last one
   winning (`--indent 3 -c` is compact, `-c --indent 0` → `[\n1\n]`); `-M` beats `-C` in either
   order. jq's interactions, each a required case:
   - under `ascii()` a string is written as JSON even when raw (`-r -a` on `"é","abc"` →
     `"é"`, `"abc"`), and `--raw-output0 -a` prints a string holding NUL;
   - `seq()` writes RS before every result except a raw string (`--seq -r '"a",1'` → `a\n\x1e1\n`);
   - `--raw-output0` on a string holding NUL ends with rc 5, and no `try` catches it;
   - `-S` sorts only what the writer prints (`keys_unsorted` → `["b","a"]`, `tojson` unsorted);
   - one bad `JQ_COLORS` field resets every colour (`0;31:xyz` prints null in `0;90`).
10. **A frozen `malformed-v1` generator** (a), recorded whole before the replay ticket and frozen on
    a dry run with bounds written beforehand (as #58). Its forms cover GATE check 3's routes and
    modes (`-c .`, `try input catch .`, `-s`, `--seq`, `--stream`, `fromjson`). NUL only inside
    strings (ADR-0023: generators avoid deviation shapes). No `test_transform`.
11. **Diagnostic rows plus the 10 % bar** (a). The draft's rows are measured first, on the pre-spec
    reader; a ticket that changes the fast loop measures against them in one session (as #85) and
    does not merge over the bar. Added row: `inputs | .a` over 10 000 × 1 KB, in 5a and in 5b
    (ADR-0019's "detach of inputs"). No stream cell joins the gate.
12. **Split: 5a "JSON in full", 5b "holds, root use, host streaming"** (a); ADR-0023 allows it.

### Decided beyond the draft

- **A1. `-R` and `-Rs` decode each read block on its own**, as jq does (`util.c` calls
  `jv_string_sized` per block). For `A E2 z z` with E2 at offset 4089 of a line jq-ref prints
  `A�z` (the cut sequence takes the block's last byte, F-26's rule), on a short line
  `A�zz`; 2 018 of 9 304 probed (bytes, offset) pairs differ so; a JSON string does not.
- **A2. `--seq` texts come from the translation; the RS split stays outside the fast loop** (`scan`
  handles RS before every state, strings included). ADR-0015's "a cut document followed by RS
  vanishes without a message" holds only after whitespace: `\x1e[1\x1e2` → "jq: ignoring parse
  error: Truncated value at line 1, column 4". Empty input also prints an ignored error, rc 0.
- **A3. `fromjson`'s lenient and malformed cases belong to 5a.** Spec 4 builds it for strict input;
  jq.test 2315, 2319 (its input is `{"a":nan}`), 2324 and 2498 move to spec 5 in the census.
- **A4. `inputs` runs in constant depth**: it is `try repeat(input) catch …` (`builtin.jq` 189) and
  rests on 2b's tail jump. Required: more documents than a small `recursionDepth`.
- **A5.** `$__prog_args` is not in jq 1.8.2 (P7: "$__prog_args is not defined", rc 3) and leaves
  spec 7's list. A missing input file is 5a's: "jq: error: Could not open file nope: No such file
  or directory", the other files still run, rc 2.

### Probe facts for the tickets (jq-ref 1.8.2, adviser)

- Position: `(at <unknown>)` before any read (`jq -n --raw-output0 '"a\u0000b"'`); `-n` gives
  `[input_filename, input_line_number]` → `[null,0]`.
- Files: a document spans files (`[4,\n` then `5]\n6` → `[[4,5],"d",1]`); `-R` lines join across
  files (`5]\n6` then `1\n2\n` → `"5]"`, `"61"`, `"2"`).
- Slurp: `-s` with a parse error prints nothing, rc 5; `-s --stream` with an error prints no events;
  `-s --seq` skips bad texts and still prints its one array (`[1,2,3,4]` after two ignored errors).
- `-e`: `true false` → rc 1; `select(.)` on `true false` → rc 0.
- `--indent`: 0 after `-c` is pretty with no indent; `--indent -1` prints as `--tab`.
- Colour: `-C` beats `NO_COLOR`; an empty `NO_COLOR` is unset; fewer `JQ_COLORS` fields keep the
  rest and a ninth field is accepted; a raw string is never coloured; `[]` and `{}` are coloured as
  one unit.
- `--raw-output0`: a non-string result gets its NUL too.
- `--seq`: `\x1e"a\x1eb"` gives "Truncated value at line 1, column 4" and then "Invalid numeric
  literal at line 1, column 6 (need RS to resync)"; a BOM before the first RS is accepted.
- Path mode: `path(input)` → "Invalid path expression with result 1".

### Ticket shape (approved by the adviser for the owner): 18 (11 class C, 7 class B)

5a (15): 1. malformed-v1 (B); 2. reader diagnostics, baseline first (B); 3. lenient tokens;
4. `JvParser` replay with the NUL line and the position-less texts, closing #76; 5. the input
stream reader: `read(JsonInputs)`, several values, read blocks, files; 6. `input`, `inputs`,
`input_filename`, `input_line_number`, tape B, resume, detach at read [2b]; 7. command line: files,
`-n`, `-e`, rc over several inputs (B); 8. `-s`, `-R`, `-Rs`; 9. `--stream` [3]; 10. `--seq` in and
out; 11. the withers with `--indent`, `--tab`, `-S`, `-a`, closing #98; 12. `-r`, `-j`,
`--raw-output0` (B); 13. `-C`, `-M`, `NO_COLOR`, `JQ_COLORS` (B); 14. `fromjson` through the replay
[4] (B); 15. shtest groups translated and recorded (B, raised from A: it judges which groups drop
or wait for spec 7). Only 6 [2b], 9's `tostream` property [3], 14 [4] and parts of 15 need
another spec.

5b (3, all C): holds, with its `explain()` column; root use with `needsWholeDocument` [3];
`stream()` with the fallback, DEVIATIONS' `test:` rows and `streamAllocationFlat`.

### Records to write

- New ADR: host streaming and root use (question 2); amends ADR-0004, 0007, 0016; DEVIATIONS
  "Streaming" gains the sink-stop line. Glossary: **Element document** (the document one streamed
  element is read into, on the Workspace's reused element tape, with its root at index 0; it goes
  stale when `rest` returns for that element. _Avoid_: chunk, item).
- Amend ADR-0015 (questions 3, 4, 6, 8, 9, A1, A2), ADR-0016 with ADR-0024 (1, 9), ADR-0002 and
  ADR-0012 (5), ADR-0007 (7), ADR-0018 and the glossary's "Conservative compile" (7), ADR-0019
  (11), ADR-0020 (10), ADR-0023 with GATE's table (12, A3, A5).
- Findings: no exported pull for main documents; `--stream`'s own texts; NONFINITE never written;
  the `--seq` cut text; `-R` per read block; `fromjson`'s census rows; the pipe as a hold.
- DEVIATIONS "NUL": question 8's lines; the reason says 1.8.2 already holds the INVALID branch and
  master's `classify` reaches it (source read, not run).
- NOTICE: `JvParser` (`jv_parse.c`), the colour layout and `jq_set_colors` (`jv_print.c`), shtest.

### Open

- Round 2: not needed for 5a before `/to-spec`. 5b needs one once spec 3's round 2 and 2b's
  analysis tickets are fixed: the hold sites over 2b's frames and closures, and the root-use test.
- Missing facts: the fast loop's cost with the token-boundary compare and several values per
  stream (tickets 3, 5); the cost per `input` document of detach-at-read (ticket 6's row);
  `jq_set_colors`' validity rule (`jv_print.c`, ticket 13); other spec-4 census cases needing a
  lenient token or a replay text (census sweep); colour by default on a terminal as `main.c` does
  (the JDK call by `javap`; no differential case reaches it).

## Records round (architect, 2026-11-14)

The records designer drafted ADR-0054 (host streaming), F-71 to F-77 and the amendments of round
1. The architect settled:

1. **Census.** Spec 4's M3 named jq.test 2315, 2324, 2498 and 2563; this round's A3 named 2315,
   2319, 2324 and 2498. Spec 5 takes all five (F-76, ADR-0023).
2. **Shared files.** Specs 4 and 5 amend the same ADRs. Spec 5's status additions sit at the end of
   each status line, and its amendment sections after spec 4's.

For the owner on 2026-11-16, in addition to round 1's list:
- founding §6.7 says "each with its own small tape"; ADR-0054 uses one reused element tape;
- F-77 replaces ADR-0007's owner-accepted hold list, not only ADR-0018's and the glossary's text.

Open for the tickets: what `Workspace.read(JsonInputs)` returns under `-R`, `-Rs` and `--stream`;
where a read after a `JqSyntaxException` starts outside `--seq`; the names of `JsonInputs`'
position accessors; bad `indent` and `JQ_COLORS` values; whether today's reader writes NONFINITE;
whether `malformed-v1` gets a GATE check 2 minimum.

## Ticket approval (grilling-adviser for the owner, 2026-11-14)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14. 5a's 15
tickets are approved for publishing with the changes below; 5b is not published yet (6f). The
architect re-ran the probes on jq-ref 1.8.2; where the two differ, the architect's run stands.
1. Order and edges stand as tabled, with three changes: S5a.9 names the one 3a ticket that makes
   `tostream` pass; T6, T9 and T14 publish without `ready-for-agent` until their spec 3a and spec 4
   blockers have issue numbers; T7 ← T4 and T7 ← T11 are serialising edges (one jq-cli main loop
   and option parser). S5a.5 before S5a.4 and S5a.7 before S5a.6 are approved.
2. ADR-0016 gains "Amendment (spec 5a tickets)": `InputPosition(String fileName, long line)`,
   what `read(JsonInputs)` returns per mode, the read after a `JqSyntaxException`, bad arguments.
   The architect's run corrected its `-s` sentence: `1 x] 2\n3\n` under `-s` prints no array,
   "Invalid numeric literal at line 1, column 4", rc 5.
3. `malformed-v1` (S5a.1): more mutations, 1 800 seeds (300 per form, `seed % 6`) and bound floors
   written beforehand. The diagnostics (S5a.2) take round 1's list (question 11). T5 carries the
   NOTICE line for `jq_util_input_read_more` (`src/util.c`); T8 extends it (a ticket matter).
4. Classes confirmed: S5a.1, S5a.2, S5a.12, S5a.13 and S5a.15 are B. A public name or behaviour
   outside ADR-0016 raises S5a.12 or S5a.13 to C by a ticket comment.
5. `--unbuffered` belongs to S5a.7 (the main loop flushes after each result, `main.c` 207).
   `malformed-v1` has GATE check 2's minimum of 200 per form after drops (F-78, ADR-0023).
6. Changes before publishing:
   - a. **Correction to round 1, question 3** (and F-72): the two texts were swapped. `{"a":}`
     under `--stream` → "Missing value in key:value pair at line 1, column 6"; without it →
     "Unmatched '}' at line 1, column 6", rc 5 both (`jv_parse.c` 396 and 221).
   - b. **Correction to round 1, A5**: a missing file does not let every other file run. With
     `ok1` = `1\n2\n`, `ok2` = `3\n`: `jq -c . nope ok1 ok2` → `1`, rc 2 (the main loop tests the
     open-failure count before each read, `main.c` 679); `jq -c . ok1 nope ok2` → `1`, `2`, `3`,
     rc 2; `jq -nc '[inputs]' ok1 nope ok2` → `[1,2,3]`, rc 2. Rc 2 replaces a parse error's 5 and
     `-e`'s code (`main.c` 704). ADR-0015 is amended.
   - c. `input_filename` on stdin is `"<stdin>"` (`echo 1 | jq -c '[input_filename,
     input_line_number]'` → `["<stdin>",1]`); only `-n` gives `[null,0]`. T5 drops "null on stdin".
   - d. **Correction to round 1, question 9**: `-r -a` on `"é"` prints `"é"` (architect's
     probe), as ADR-0015 already says. T12's red says so.
   - e. T6's F-77 cases and `path(input)` hold only under `-n`. Without it,
     `def f(g): [g, g]; input | f(., input)` on `1 2 3` → "jq: error (at <stdin>:0): break", rc 5,
     no output; with `-n` → `[1,2,1,3]`, rc 0.
   - f. 5b's spec issue and tickets are not published before round 2 (WORKFLOW §3 step 1 wants the
     grilling frontier empty first). Its shape is round 2's input; U2 and U3 gain "Part of".

Missing facts: `jq_set_colors`' validity rule (T13 reads `jv_print.c`); the issue numbers of the
3a Facts ticket, the 3a `tostream` ticket and S4.3; the fast loop's cost with the boundary compare
(T2, then T3 and T5); a BOM in a second file (T5 records it).
