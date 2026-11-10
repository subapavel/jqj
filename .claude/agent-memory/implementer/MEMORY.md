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
