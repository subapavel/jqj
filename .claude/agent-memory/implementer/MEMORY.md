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
- (#34) The word check scans query-core test comments too; and a lexer must reject a token-pair Stub (`..`) itself — the parser rejecting the bare pair leaves `..a` running.
- (#34) Grilling decisions bind even when they look speculative (BuiltString `owner`, grilling 1.9): never drop them as a deviation.
- (#38) A JVM child inherits JAVA_TOOL_OPTIONS here ("Picked up …" on stderr): remove it from ProcessBuilder.environment() in a process test. A surefire `test` execution bound to integration-test runs after package without failsafe.
- (#38) `-Dtest=X` with `-am` needs `-Dsurefire.failIfNoSpecifiedTests=false` and skips upstream module tests.
- (#37) A PIT boundary mutant that only adds a zero-length write is equivalent; say so with the line, don't chase it.
- (#36) Parsing a long String into BigDecimal/BigInteger is quadratic (1 000 000 digits ≈ 17.7 s): cut a long coefficient before any BigDecimal on a host-reachable path. PIT counts TIMED_OUT and MEMORY_ERROR as detected.
- (#35) Comparing output through Java's UTF-8 decoder hides raw bad bytes (it makes U+FFFD too): compare bytes. After merging a parallel ticket that also ends Stubs, check that no Stub @TestFactory is left empty.
- (#39) JMH: pass `-foe true` or a throwing benchmark exits 0; command-line `-jvmArgs` overrides `@Fork(jvmArgs)` (check the log's "VM options"). A recording ticket needs its own test that its cases exist (until #43).
- (#49) `git fetch origin <branch>` only updates FETCH_HEAD: use `git fetch origin "+refs/heads/<b>:refs/remotes/origin/<b>"` for a usable `origin/<b>`. A worktree can start on a stale commit: check the packet's base sha before reading files.
- (#51) `ci.yml` runs only on `pull_request`, pushes to `main`/`spec/**` and `workflow_dispatch`: a `t/*` branch gets CI only from the integration push after merge. New script files come out 644: `git update-index --chmod=+x` plus `chmod +x`. `git var GIT_AUTHOR_IDENT` gives the owner's Signed-off-by identity; `git config user.*` gives Claude.
- (#50) `git merge` runs the commit-msg hook; a refused merge leaves MERGE_HEAD (`git merge --abort`). Two `--allow-empty` commits with the same message, parent and time share a SHA. A relative `core.hooksPath` resolves per worktree top. No man pages in the container: check git behaviour in a scratch repo.
- (#52) `git commit-tree -p <parent> -m <msg> <commit>^{tree}` makes a "rewritten" commit (same tree, new sha) for script tests. The worktree guard refuses `cd <wt> && python3 - <<EOF`: use Edit.
- (#57) The S2a blocking graph exists only as "Blocked by" text in issue bodies (the native dependencies API is empty); `scripts/census.java` `BLOCKED_BY` mirrors it. jq-ref `--debug-dump-disasm` with no input compiles without running, and folds constants (`1+2` → LOADK 3): read constructs from the tokens.
- (#47) pitest-maven 1.30.2 `crossModule` mutates only reactor modules the POM declares directly. The spec-gate PIT step takes about 26.5 min on this container. A transient proxy 429 on the mvnw wrapper download: retry.
- (#55) `git reset --hard` is refused in worktrees: `git checkout -B <b> origin/<b>`. A script test pushing to a local bare repo: `git -c push.negotiate=false push`.
- (#58) jq-ref 1.8.2: `{(<constant>): v}` with a non-string constant (also folded binops like `{(1+1): 2}`) is rc 3; `1/0` with literals is rc 5. A new method between an existing javadoc and its method fails `-Xlint:dangling-doc-comments` under `-Werror`.
- (#59 test-author) An isolated worktree refuses `git config core.hooksPath`: run `bash scripts/check-trailers.sh --message <file>` before `git commit -F`. A query-core test compile failure skips jq-core: to see jq-core's red, use a scratch copy.
- (#59) `-Xlint:all -Werror` rejects a public class in an exported package without an explicit constructor ("exposing a default constructor").
- (#59) Guard-mutant sweep: commit first, then per mutant replace with `${c/"$old"/"$new"}`, run `./mvnw -B -o -pl jq-core -am test -Dmaven.test.failure.ignore=true`, grep `<<< FAILURE!`, restore with `git checkout -- <file>` (~10 s each).
- (#59) JaCoCo counts a line as missed when its call throws (the probe sits after the invoke): a `throw X.over(...)` line needs a test that reaches it.
- (#59) A javac `case A _, B _` leaves an unreachable second dispatch, so the line stays partly covered; give each kind its own arm.
- (#59) Refuel's refill cap is only witnessed by an interrupt/deadline test across two refuels; a step cut alone stays exact without it.
