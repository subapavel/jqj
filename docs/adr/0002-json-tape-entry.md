# JSON tape entry: one long per value

Status: accepted (2026-11-10, owner, issue #5 "What are the value model and the tape layout?")

A document's tape is one `long[]` with **one entry per JSON value, object keys included**. Each
entry is a 4-bit tag, a 28-bit span and a 32-bit offset. We chose it because it is the smallest
layout that still lets a field lookup reject most keys by length alone, and lets every container
be stepped over in one jump. Measured on the Spike B document (`bench/RESULTS.md`), it is 16.4 MB
of tape for 11.2 MB of JSON, 1.46× the input. A reused tape reads a 1 KB message in ~2.1 µs and
10 MB in ~34 ms, including UTF-8 classification and the depth limit.

- **Span**: a scalar's byte length (a string's includes its quotes), or the number of entries
  inside a container. A tag ≥ 8 has the sign bit set, so the next sibling is
  `e < 0 ? i + 1 + span : i + 1` without decoding the tag. `query-core` relies on this rule, the
  `long[]` and an `int` cursor, never on a tag's meaning (founding architecture §14).
- **Offset**: a scalar's first byte. For a container it is the **closing bracket**: the reader
  writes the entry there anyway, so the end is known at once. The start is found by stepping back
  from the first child.
- **Tags**:

  | Tag | Meaning |
  | --- | --- |
  | 0 | invalid |
  | 1 | null |
  | 2 | false (falsy ⇔ tag ≤ 2) |
  | 3 | true |
  | 4 | number |
  | 5 | string whose bytes are its value and its compact output |
  | 6 | string that must be re-encoded (escapes, DEL, invalid UTF-8, which jq turns into U+FFFD) |
  | 7 | reserved scalar |
  | 8 | array |
  | 9 | object |
  | 10 | reserved: object known to hold a duplicate key |
  | 11–14 | reserved for other formats |
  | 15 | reserved: wide (two-long) entry |

- **Numbers**: one tag. The span holds 4 flags (FITS_LONG, CANONICAL, NONFINITE, one spare) and a
  24-bit length. Their meaning is given by ADR-0012.
- **Limits**: 4 GiB per document, 256 MiB per string (the owner confirmed this is enough for
  Flottila), 16 MiB per number literal, 2^28 entries per container. Beyond a limit the reader
  raises a jq error. The wide tag lifts these limits later if ever needed.
- **Duplicate keys are found on first touch, and the tape is read-only after the read.** `.a`
  scans to the last match. `keys`, `length`, `.[]` and the writer check again on every pass.
  jq keeps the first position with the last value: `{"x":0,"a":1,"b":2,"a":3,"x":9}` →
  `{"x":9,"a":3,"b":2}` (observed on jq-ref 1.8.2).
- The layout gives nothing extra for copying a whole container byte for byte. The writer decides
  that with one walk over the container's entries ("How do the JSON reader and writer behave?").

## Considered options

- **Two longs per value**: 32.8 MB on the same document and twice the reader's tape writes, for
  room nobody needs yet.
- **Keys fused into their value's entry**: 38 % smaller, but every key compare reads document bytes,
  key views need a second form, and a fifth tag bit halves the limits.
- **Entries for containers only**: ~85 % smaller, but every access re-tokenises bytes, against
  founding §5.
- **Container offset at the opening bracket** (the Spike B prototype): the end then needs bracket
  matching. **An extra END entry per container**: +15 % tape.
- **Duplicate detection in the reader**: +32 % at 1 KB and 100 KB, +43 % at 10 MB, measured
  (`bench/RESULTS.md`, "Tape reader for milestone 1"). That is over the owner's ~10 % line, and a
  cheaper detector still cost +56 % on a 78 841-key object.
- **Writing a duplicate-check result into the tape**: it would make the tape mutable during an
  evaluation, which matters once a document is shared.
