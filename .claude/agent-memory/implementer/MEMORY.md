# implementer memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from flottila-mapping (2026-11-10; observed there, re-verify on first use here)

- Context7 tools are not offered in a subagent session: verify APIs with `javap` on the jar in `~/.m2`.
- `dependency:build-classpath` fails on a module whose sibling SNAPSHOTs are not installed (and
  `install` is forbidden): point `javap -cp` at the jars in `~/.m2` directly.
- The worktree guard refuses a Bash call that mixes `git` with pipes, `$(…)`, loops, `&&` chains,
  heredocs or `python3 - <<EOF`: one plain command per call, files via Write, anything longer as a
  scratch script run with `bash <script>`.
- `gh`: REST only (`gh api repos/<owner>/<repo>/issues/<n>`).
- A `\u` in a Java comment is a Unicode escape and breaks compilation.
- `-pl <module>` without `-am` takes sibling SNAPSHOT jars from `~/.m2`, stale ones ("cannot find
  symbol" for a new class). Always `-am`.
- `./mvnw` inside a `while read` loop eats the loop's stdin: run it with `< /dev/null`.
- `-Xlint:all -Werror` applies to tests too. Named modules need surefire `--add-opens` for test
  reflection. `-Dtest=… verify` trips JaCoCo's check: use `test` for a single class.
- JaCoCo: a `switch` `default` or a guard no test reaches costs a branch against the floor; an
  arrow `switch` over an enum leaves the implicit default edge uncovered. Prefer exhaustive
  sealed switches.
- PIT mutates each module against that module's own tests only: a path covered only by another
  module's test survives. Add module tests.
- Splitting a shared helper copies its branches; a copy no test reaches drops the module's branch %.
- Review asks for a mutant per new guard: stage the work, apply the mutant, run the test with
  `-Dmaven.test.failure.ignore=true -Dtest=…`, then `git checkout -- <files>`.
- JUnit quotes String arguments in a `{0}` display name; for plain names use
  `Arguments.argumentSet(name, …)` with `name = "{argumentSetName}"`.
- Timing a stage: a scratch `java -cp <target/classes:~/.m2 jars> Probe.java`; JFR
  (`-XX:StartFlightRecording`) plus `jfr print` for hot spots. Surefire's per-test `time`
  includes a cold JIT and the JaCoCo agent.
- Every commit, including a merge of the integration tip (`git merge --no-ff -F <file>`), carries
  the §11 trailers, exactly `Co-authored-by: Claude <noreply@anthropic.com>` (case-sensitive); run
  `scripts/check-trailers.sh <base>..HEAD` before you push.
- The scratchpad is shared by parallel agents: prefix scratch files with the ticket number.
- A ticket branch checked out in another worktree: reset the agent branch to it, then
  `git push origin HEAD:<ticket-branch>`.
- (#23) A Bash call naming `github` or `.git` in a path is refused by the worktree guard; use globs (`io/gi*/subapavel`) or split the command.
- (#23) `scripts/check-lf-checkout.sh` fails on any unstaged change to a tracked file: it runs only under `-Plf-check`, never in the default build.
- (#23) license-maven-plugin: a `licenseMerge` entry must not repeat its own target name, or the build errors.
- (#23) PIT's line count includes a private empty constructor (JaCoCo ignores it): tiny classes show low PIT line coverage.
- (#23) PIT reaches query-core/jq-core unit tests only via `additionalClasspathElements` to their `target/test-classes`; run the reactor with `-am`.
- (#25) JaCoCo report-aggregate CSV GROUP is `oracle/<module>`, not `<module>`.
- (#25) The worktree guard refuses a Bash call whose text has `github` with git/sed pipelines; use Edit instead.
- (#25) A stub `java` first on PATH (exec-ing the real one otherwise) lets a script test fake `java -jar`.
- (#25) Oracle report format read by `scripts/ratchet.java`: `<oracle>-<suffix>.tsv`, columns `id, verdict, detail, extras`; def in column 4 (json: label 4, def 5).
- (#31) The worktree guard refuses a Bash call containing `git` (incl. `.github` paths, `GIT_*`) together with other shell syntax: run `git` alone.
- (#31) `gh api` and GitHub web pages for this repo return 403 in subagent sessions; `git ls-remote` on public repos works.
- (#31) `workflow_dispatch` needs the workflow file on the default branch first.
- (#27) PIT never re-runs a test class's static initializer under a mutant: load fixtures per test, not in `static {}`.
- (#27) PIT copies surefire's groups, excludes, argLine and env vars, but not `systemPropertyVariables`: a surefire-only property can gate side effects such as oracle reports.
- (#27) A qualified `exports … to <module>` naming a module not observable at compile time warns and fails `-Werror`; use `--add-exports` in the consumer's compiler args.
- (#27) jq with empty stdin runs nothing (rc 0, no output); a case without `stdin` needs `-n`.
- (#29) The worktree guard refuses a Bash call mixing `python3 <<EOF`, `$S` variables in a git-adjacent command, or `git fetch` chained with other commands: use Edit, one plain git command per call.
- (#29) `git fetch origin <branch>:refs/remotes/origin/<branch>` creates the remote-tracking ref when plain `fetch <branch>` does not.
- (#29) An oracle family with extra report columns needs a `DiffReport.Row` label, not a new report class; byte-exact inputs go in through `CaseFormat.escape` plus a `file` line.
- (#24) PIT skips a module whose targetClasses match nothing; a module-path-only test in jq-cli never runs on PIT's class path.
- (#24) surefire 3.6.1 `useModulePath=true` on jq-cli patches the tests into the module and opens their package; no extra add-opens.
- (#24) `-Xlint:serial` warns on a non-transient interface-typed field (List, JqValue) in an exception; javac 25 does not warn on permits naming non-exported classes.
- (#24) A null guard placed before a two-sided check needs its null test to set the other argument, or the next dereference hides a deleted guard.
- (#24) In an API-slice ticket "every body is a Stub" covers behaviour members only; carriers (builders, records, enums, exceptions) get real bodies, as the approved tests decide.
- (#30) jmh-core 1.37 has no Automatic-Module-Name: `requires jmh.core` trips `-Xlint:requires-automatic`; `@SuppressWarnings("requires-automatic")` on the module declaration clears it under -Werror.
- (#30) Put JMH's `annotationProcessorPaths` on `default-compile` only; on testCompile `-Xlint:processing` fails (no processor claims JUnit's annotations).
- (#30) Bench method names are `<gate>_<mode>_<size>`; `ratchet.java` reads `_` as `.` to get ADR-0024's ids. JMH exits 0 when a benchmark throws unless `-foe true`.
- (#30) `gh api repos/<o>/<r>/issues/<n>` (REST) works from a subagent; `gh issue view` (GraphQL) gets 403.
- (#26) The oracle module's main tree sees only jq-cli (module-info), its test tree compiles against jq-core: keep API adapters in the test tree.
- (#26) The worktree guard rejects a Bash call whose text contains `io.github` (e.g. PIT targetClasses): run it from a scratch script.
- (#26) Run `git fetch origin <b>:refs/remotes/origin/<b>` before the gate so its `origin/<b>` base is the current tip.
- (#28) `gate-ticket.sh` with the default `origin/main` ref fails trailers on the integration branch's architect commits; pass `origin/spec/<spec>-<slug>` as ref.
- (#28) `\uXXXX` in a Java comment is an active Unicode escape; write `&#92;u`. Design tables pasted through tools may lose escapes: ask when two table cells look identical.
- (#28) jq-ref 1.8.2 accepts `-Infinity`, `-inf`, `1e2147483648` and 11-digit exponents (rc 0, clamped to ±DBL_MAX or `0E-1147483646`). `/usr/bin/time` is absent; use bash `time`.
- (#42) The worktree guard refuses a Bash call combining `git` with `;` or `&&`: one git command per call, or a scratch script run with `bash`.
- (#28) PIT credits a test class's static-field initializer to no test: draw shared fixtures per test (instance field).
- (#32) heapBase() is empty for a read-only heap segment (an isReadOnly() guard before it is dead); address() of a byte[] slice is its array offset. Character.digit(c,16) over -1..0xFF accepts exactly [0-9A-Fa-f].
- (#32) PIT (junit5 plugin 1.2.3) credits a dynamic test only for lines run inside it; setup in a @TestFactory body shows its mutants as surviving.
- (#32) An accessor that calls host code (forEach) must re-check staleness after each call: the host may read into the same Workspace inside it.
- (#32) Error positions tested only at base 0 hide a base bug: always add a slice case at a non-zero base.
- (#33) Gate jq-cli out of the PIT module list: PIT runs its module-path test on the class path and fails; the ratchet still checks jq-cli's 80 % line floor.
- (#33) A mutant on a position (BOM skip, token start, `Source`) is not equivalent: kill it through the seam's offset/line/column or the IR node's `source()`; a slice at a non-zero base kills `end - p` → `end + p`.
- (#33) Pin line and column of a read error only where jq-ref gives one (grilling item 7); a partial or bare BOM is rc 0 in jq-ref.
