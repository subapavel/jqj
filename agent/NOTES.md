# Notes

Module notes by section, read on demand.

## Cloud environment (carried over from flottila-mapping, observed there 2026-10-30; re-check in the first session here and replace this section with what was observed)

- `gh issue …` and `gh pr …` failed there with HTTP 403: GraphQL is blocked from Claude Code
  sessions. `gh api repos/{owner}/{repo}/issues` (REST) worked. Use the "Cloud VM: REST only"
  table in `docs/agents/issue-tracker.md` for every issue operation until observed otherwise.
- `gh auth status` reported the token invalid; the proxy authenticated REST calls anyway.
- `gh` was 2.89.0 (< 2.94: no `--blocked-by` / `--add-sub-issue` flags).
- Context7: without `CONTEXT7_API_KEY` the free quota ran out; `.mcp.json` sends the key as a
  header. The server stays "Pending approval" unless `~/.claude/settings.json` lists it in
  `enabledMcpjsonServers` (`deploy/claude-cloud/setup.sh` writes that). A session started on more
  than one repository begins in `/home/user` and loads no `.mcp.json`.
- Context7 tools were not offered inside subagent sessions: subagents verify APIs with `javap`.
- Maven Central answered HTTP 429 to the VM; the setup script points Maven and the wrapper at the
  Google mirror of Central.
- Tags could not be pushed from the VM.

## Checks to record in the first session

1. `pwd`, `java -version`, `./mvnw -v` once the wrapper exists.
2. `gh api repos/{owner}/{repo}/issues` and whether `gh issue list` works.
3. `claude mcp list` shows context7 connected; one `resolve-library-id` call.
4. `/usr/local/bin/jq-ref --version` equals the version pinned in `deploy/claude-cloud/setup.sh`.
