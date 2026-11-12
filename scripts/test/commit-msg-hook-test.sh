#!/usr/bin/env bash
# Script test for scripts/hooks/commit-msg (#50): the hook checks one message file with the rules of
# scripts/check-trailers.sh, for git commit and git merge alike. Builds a throwaway repo whose own config sets
# core.hooksPath; the shared repository's config is never touched.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

repo=$tmp/repo
mkdir -p "$repo/scripts/hooks"
g() { git -C "$repo" "$@"; }
g init -q -b main
# the hook and the script work in the repository they live in, so the throwaway repo holds its own copies
cp "$here/../check-trailers.sh" "$repo/scripts/check-trailers.sh"
cp "$here/../hooks/commit-msg" "$repo/scripts/hooks/commit-msg"
g config user.email a@b.c
g config user.name t
g config core.hooksPath scripts/hooks
g commit -q --allow-empty --no-verify -m base

# hook <file>: runs the hook as git does (from the top of the work tree), output in $tmp/hook.out
hook() { (cd "$repo" && scripts/hooks/commit-msg "$1" > "$tmp/hook.out" 2>&1; echo $?); }

# the hook rejects a message missing one of the three trailers, with the text check-trailers.sh prints
printf 'feat: x\n\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n' > "$tmp/msg"
check "missing Ticket: hook rc" "$(hook "$tmp/msg")" 1
check "missing Ticket: hook text" "$(cat "$tmp/hook.out")" "commit message lacks 'Ticket: #<n>': feat: x"
printf 'feat: x\n\nTicket: #3\nSigned-off-by: o <o@x.y>\n' > "$tmp/msg"
check "missing Co-authored-by: hook rc" "$(hook "$tmp/msg")" 1
check "missing Co-authored-by: hook text" "$(cat "$tmp/hook.out")" \
  "commit message lacks 'Co-authored-by: Claude <noreply@anthropic.com>': feat: x"
printf 'feat: x\n\nTicket: #3\nCo-authored-by: Claude <noreply@anthropic.com>\n' > "$tmp/msg"
check "missing Signed-off-by: hook rc" "$(hook "$tmp/msg")" 1
check "missing Signed-off-by: hook text" "$(cat "$tmp/hook.out")" \
  "commit message has 0 Signed-off-by lines, expected exactly 1: feat: x"

[ $fails -eq 0 ] && echo "ALL PASS"
exit $fails
