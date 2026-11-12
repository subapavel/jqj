# test-author memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- `-Xlint:all -Werror` applies to test sources: no unchecked casts, no generic varargs without
  `@SafeVarargs`, no static call through an instance.
- A test-compile failure in a module skips every module that depends on it in the same run.
- Bash refuses some compound commands (heredocs, `git` in loops) in a worktree: write files with
  the Write tool, run plain commands.
- Validating expectations before the code exists: compile the tests against stand-in types plus a
  reference implementation in the scratchpad (never committed).
- A red that can fail for an unrelated reason is not the red: also show the behaviour red.
- Pin a literal expected value; never recompute it in the test the way the code computes it.
- (#24) `assertThatThrownBy(..).as(..)` loses the description when nothing is thrown; use `assertThatExceptionOfType(T).as(..).isThrownBy(..)`.
- (#24) JDK 25 `Class.toGenericString()` prints sealed/non-sealed/enum/record; record equals/hashCode/toString are `public final`; enum `values()`/`valueOf` are not synthetic, `$values`/`$VALUES` are.
- (#24) A surefire second execution with `useModulePath=true` runs a jq-cli test with jq-core as a named module.
- (#24) To prove red tests can go green without touching the branch: `cp -r` the worktree to the scratchpad, `rm` its `.git` file, write a stand-in, build with `-Dchecks.skip=true`.
- (#24) Stub tests lose entries as bodies are built; deleting an entry with its Stub is not weakening a test — say so in the test's Javadoc.
- (#24) A null-argument test must set every other argument so that only the null guard can produce the expected exception.
- (#32) A stale-view test whose callback touches a view is vacuous: the throw comes from the callback, not the accessor under test. Make later callback steps touch nothing.
- (#32) A guard-killing input can be absorbed by EOF (`"\u12x"`, `[tru]`): prove each kill with a hand mutant and add a twin that does not end at EOF.
- (#32) In bash, `printf '-x'` fails (use `printf --`); `\0002` is not NUL (use `\x00`). A scratchpad reference implementation validates red tests in about 10 minutes.
- (#32) jq's depth rule: reject `[`/`{` when the parser stack (open containers plus open key values) already holds 10 000 (F-22).
- (#33) Search the whole repo for older tests that use the construct you are building as their Stub witness (here `compile(".")`, Main on a `.` case); list them as conflicts in the hand-back.
- (#33) AssertJ 3.27.7 deprecates `hasCauseReference` (-Werror rejects it); a required-case file lives at oracle/src/test/resources/require/<ticket>.txt.
- (#34) Every row of an expected-value table needs its own recording or cited jq-ref run; a whitespace-skip set needs the closing byte at a block cut followed by each whitespace byte.
- (#34) Case ids are `<family>/<name>` (ADR-0024's `bench.G1.1k` is `bench/G1.1k`); long inputs go inline as escaped `stdin` lines.
