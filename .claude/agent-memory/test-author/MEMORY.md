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
