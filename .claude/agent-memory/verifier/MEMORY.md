# verifier memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- The shell does not keep a `cd` outside the repo: run a gate as one plain command by absolute
  path, `/…/verify-<ticket>/scripts/gate-ticket.sh <args>`, no `bash -c`, no `cd`, no redirect.
- Always gate in a fresh `verify-*` worktree: a stale `jacoco.exec` fails coverage.
