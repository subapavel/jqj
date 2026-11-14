# Every body kind has a heap form on the form-B driver; U is derived per filter and run; the depth budget counts calls

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 2b round 1); records F-55; amends ADR-0011 (the trampoline's kinds, U, the calibration, what the depth budget counts) and ADR-0017 (what the recursion-depth budget counts; U per run)

ADR-0011 runs recursion beyond U on a resumable form-B trampoline and makes U a constant per
release. Building it in 2b raises three questions. Which kinds must the driver run? What is U for a
filter whose own nesting and declared stack differ from the calibration's? What counts as one level
of the recursion-depth budget?

## Decision

- **Every kind in a body has a heap form.** The form-B trampoline runs every kind that can sit in a
  function or closure body, 20 after 2b, each with a resumable heap form. jq-ref on a 9 999-deep
  array, about 10 ms each: `def d: [.[]?|d]; d|length` → `1`;
  `def r: reduce .[] as $x (0; .+($x|r)+1); r` → `9998`; `def l: first(.[]?|l)//7; l` → `7`;
  `walk(.)|length` → `1`. A spine-only driver keeps only the calls on the heap and every other kind
  on the stack. It would end these near 200 levels, against ADR-0011, under which a memory-bounded
  recursion-depth budget limits the depth. `walk/1` stays red until spec 3; 2b's deep cases use
  user recursion.
- **Form B routes downstream events back into the driver** (M4). Two things go back into the driver,
  at the heap frame that produced the result: an error raised downstream of a result the starter
  emits, and the emit's answer (a stop or a break). So a heap `AltBind` still catches the error
  (ADR-0038), and the heap frames stop. Required cases beyond U (shapes observed on jq-ref at
  depth 3):
  `def f: if .==0 then ([[1]]|.[] as [$a] ?// $a|$a) else (.-1|f)|. end; [3|f|if .==1 then error("x") else . end]`
  → `[[1]]`; `[label $out | def f: if .==0 then (1,2) else (.-1|f)|. end; 3|f|., break $out]` →
  `[1]`.
- **U per filter** (F-55). The release constants are the bytes per unit `b` and a guard reserve.
  Each is taken at the worst of `-Xint` (the deterministic cold column), C1 only and warm, in fresh
  agent-free JVMs, on 512 KiB, 1 MiB and a virtual thread. A compile stores `D_f`, the filter's
  deepest call-free nesting. A run derives `U_f = (stackBytes − reserve) / b − D_f` at its start,
  because `stackBytes` can be set per run (ADR-0017).
- **Where the stack is tested**: in-suite tests run on a thread of at least 4× the declared
  `stackBytes`. The 512 KiB edges run only in fresh child JVMs (F-44, F-49). The calibration's full
  matrix runs at the spec gate and nightly; its `-Xint` 512 KiB column runs in the ticket gate.
- **The recursion-depth budget counts** one per live PLAIN or RECURSIVE `Call` and one per
  real-closure `ParamCall`, on the stack and on the driver together. A tail jump replaces its
  activation (ADR-0047). An inlined or specialised call counts nothing. A mode difference that ends
  in a termination is no disagreement (ADR-0020). Required: `0|until(.>=10000000; .+1)` →
  `10000000` (jq-ref, 3.4 s).
- **Not decided here**: the embedded default of 4 194 304 (ADR-0017) waits for the calibration
  ticket's heap bytes per live activation. Spike A's trampoline showed 290–600 MB RSS at 10⁶
  levels, so the default may exhaust a heap before it fires, and `OutOfMemoryError` is no
  termination. Until then the depth witness runs with a small `recursionDepth`. Also open: the
  values of `b` and the reserve, and whether the reserve covers natives and the sink at the deepest
  emit on a 10 000-deep value (`tojson`, sort and Detach are unchecked; the Printer has its own
  stack).

## Considered options

- **Spine-only heap forms**: they end near 200 levels on the shapes above.
- **A constant U per release** (ADR-0011 as written): see F-55.
- **U from the declared stack alone, without `D_f`**: a body nested deeper than the calibration
  shapes reaches the end of the stack before U. That is the failure ADR-0011 found for a fixed K.
- **Constants from the warm column only**: F-49 showed the 512 KiB edge moving with the JIT tier;
  `-Xint` is the column that does not move.
- **512 KiB edges in the suite**: an agent's frames move them (F-44).
- **Counting every call, inlined ones too**: an inlined call holds no frame and no cells, so the
  budget would count stack it does not use.
