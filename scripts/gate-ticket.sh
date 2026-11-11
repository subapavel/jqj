#!/usr/bin/env bash
# The ticket gate (ADR-0014). Runs its steps in order, logs each to .logs/<ticket>-<step>.log, appends its
# wall-clock milliseconds to .logs/<ticket>-steps.tsv (scripts/gate-spec.sh reads them), and stops at the first
# failing step with that step's exit code.
#
#   scripts/gate-ticket.sh <ticket> <modules> [ref]
#
#   <modules>  comma-separated modules the ticket changes, e.g. query-core,jq-core
#   [ref]      what "changed" means for PIT and the trailer check; default origin/main
#
# Steps: 1 verify       ./mvnw -B -Pbench verify (the whole reactor; the oracle needs every module)
#        2 pit          PIT on the classes changed since <ref> in <modules>, against the floor
#        3 lf, trailers, word, record   LF check, trailer check, word check, oracle recordings changed since <ref>
#        4 ratchet      java scripts/ratchet.java check
# Environment: ORACLE_REQUIRE, a file of the ticket's required case ids, becomes -Doracle.require=<file> (ADR-0020).
set -uo pipefail
if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "usage: scripts/gate-ticket.sh <ticket> <modules> [ref]" >&2
  exit 2
fi
ticket=$1
modules=$2
ref=${3:-origin/main}
cd "$(dirname "$0")/.." || exit 2
mkdir -p .logs
: > ".logs/$ticket-steps.tsv"

now_ms() { date +%s%3N; }

# step <name> <show> <command…>: show=1 prints the whole log, otherwise only its tail on failure.
step() {
  local name=$1 show=$2 started rc
  shift 2
  echo "== $name: $*"
  started=$(now_ms)
  "$@" > ".logs/$ticket-$name.log" 2>&1
  rc=$?
  printf '%s\t%s\t%s\n' "$name" "$(( $(now_ms) - started ))" "$rc" >> ".logs/$ticket-steps.tsv"
  if [ "$show" = 1 ] || [ "$rc" -ne 0 ]; then tail -n 40 ".logs/$ticket-$name.log"; fi
  if [ "$rc" -ne 0 ]; then
    echo "gate-ticket: step $name failed with exit code $rc (log .logs/$ticket-$name.log)" >&2
    exit "$rc"
  fi
}

require=()
if [ -n "${ORACLE_REQUIRE:-}" ]; then require=("-Doracle.require=$ORACLE_REQUIRE"); fi

step verify 0 ./mvnw -B -Pbench "${require[@]}" verify

# Step 2: the classes changed in <modules> since <ref>, as io.github…Foo* (the star takes the inner classes along).
IFS=, read -r -a mods <<< "$modules"
classes=()
while IFS= read -r path; do
  for m in "${mods[@]}"; do
    case "$path" in
      "$m"/src/main/java/*.java)
        rel=${path#"$m"/src/main/java/}
        rel=${rel%.java}
        case "$rel" in module-info | */package-info) continue ;; esac
        classes+=("${rel//\//.}*")
        ;;
    esac
  done
done < <(git diff --name-only --diff-filter=AM "$ref"...HEAD)

if [ ${#classes[@]} -eq 0 ]; then
  echo "== pit: no changed class in $modules since $ref, skipped"
else
  target=$(IFS=,; echo "${classes[*]}")
  step pit 0 ./mvnw -B -pl "$modules" -am test org.pitest:pitest-maven:mutationCoverage "-DtargetClasses=$target"
  step pit-floor 1 java scripts/ratchet.java floor-mutation "${mods[@]}"
fi

step lf 0 scripts/check-lf-checkout.sh
step trailers 0 scripts/check-trailers.sh "$ref..HEAD"
step word 0 scripts/check-core-neutral.sh
recordings=()
while IFS= read -r path; do recordings+=("$path"); done < <(git diff --name-only --diff-filter=AM "$ref"...HEAD -- '*.rec')
if [ ${#recordings[@]} -gt 0 ] && [ -x scripts/oracle-record.sh ]; then
  step record 0 scripts/oracle-record.sh --verify "${recordings[@]}"
fi

step ratchet 1 java scripts/ratchet.java check
echo "gate-ticket: $ticket passed"
