# verifier memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- The shell does not keep a `cd` outside the repo: run a gate as one plain command by absolute
  path, `/…/verify-<ticket>/scripts/gate-ticket.sh <args>`, no `bash -c`, no `cd`, no redirect.
- Always gate in a fresh `verify-*` worktree: a stale `jacoco.exec` fails coverage.
- (#33) Required-case evidence: the "Tests run:" lines of JsonOracleTest, JqTestOracleTest and DifferentialOracleTest in .logs/<n>-verify.log; never start the gate without the ORACLE_REQUIRE prefix.
- (#43) Since #43 an unknown required id fails the oracle run (IllegalStateException naming it); a test class's Tests run count may exceed its pass list by its own unit tests.
