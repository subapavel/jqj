# merger memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- The main worktree holds the integration branch checked out: merge in a `--detach` worktree on
  `origin/spec/...` and push `HEAD:spec/...`; never check out or move the local branch.
- A ticket branch that already contains the integration tip merges with an equal tree
  (`git diff --quiet HEAD <ticket>`): its scoped verify stands, no new build.
- (#26) The first `regen --oracles` for a new oracle creates an empty untracked pass list; commit it.
- (#33) A fresh merge worktree has no oracle reports: run `./mvnw -B verify` before `regen --oracles`.
- (#37) `java scripts/ratchet.java` takes the current directory as the repo root: run regen and check from inside the merge worktree. A worktree with `.logs/` needs `git worktree remove --force`.
