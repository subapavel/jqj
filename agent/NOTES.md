# Notes

Module notes by section, read on demand.

## Cloud environment (observed in the first jqj session, 2026-11-10)

1. Shell and JDK — `pwd` is `/home/user/jqj` (single-repository session). `java -version`:
   VERIFIED_PASS, `java version "25.0.5.0.3" 2026-10-25 LTS` (HotSpot). `./mvnw -v`: NOT_RUN,
   the wrapper does not exist yet; re-check once it lands.
2. GitHub — `gh` is 2.89.0 (< 2.94: no `--blocked-by` / `--add-sub-issue`).
   `gh api repos/subapavel/jqj/issues`: VERIFIED_PASS (exit 0, `[]`). `gh issue list`:
   EXECUTED_FAIL, HTTP 403 "GitHub GraphQL is not available from Claude Code sessions". Use the
   "Cloud VM: REST only" table in `docs/agents/issue-tracker.md` for every issue operation.
   `gh auth status` reports the `GH_TOKEN` invalid, yet the proxy authenticates REST calls.
3. Context7 — `claude mcp list`: VERIFIED_PASS, `context7 … √ Connected`; `CONTEXT7_API_KEY` is
   set and `~/.claude/settings.json` lists context7 in `enabledMcpjsonServers`. One
   `resolve-library-id` call ("Jackson"): VERIFIED_PASS, returned `/fasterxml/jackson-core` and
   others. Context7 inside subagent sessions: NOT_RUN (carried over: it was not offered there;
   subagents verify APIs with `javap`).
4. Reference jq — `/usr/local/bin/jq-ref --version`: VERIFIED_PASS, `jq-1.8.1`, equal to
   `JQ_REF_VERSION=1.8.1` in `deploy/claude-cloud/setup.sh`.

Carried over from flottila-mapping and not re-checked here (NOT_RUN): Maven Central answering
HTTP 429 (the setup script points Maven at the Google mirror; `~/.m2/settings.xml` exists), and
tags not pushable from the VM.

## Checks to record in the first session

1. `pwd`, `java -version`, `./mvnw -v` once the wrapper exists.
2. `gh api repos/{owner}/{repo}/issues` and whether `gh issue list` works.
3. `claude mcp list` shows context7 connected; one `resolve-library-id` call.
4. `/usr/local/bin/jq-ref --version` equals the version pinned in `deploy/claude-cloud/setup.sh`.
