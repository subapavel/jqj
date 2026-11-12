#!/usr/bin/env bash
# The spec gate (ADR-0014, ADR-0023). Reports the slowest ticket-gate step 1 found in .logs/ against its target
# (the ticket gate has no PIT step since F-27), then runs its steps in order and stops at the first failing step with that step's exit code.
#
#   scripts/gate-spec.sh <spec>
#
# Steps: 1 clean-verify   ./mvnw -B clean verify -Pbench
#        2 pit            PIT on the whole class-C modules of quality/floors.tsv
#        3 jmh            benchmarks.jar -prof gc -rf json (JMH_JAR overrides bench/jmh/target/benchmarks.jar)
#        4 regen          java scripts/ratchet.java regen --all (RATCHET_MACHINE labels the bench lines)
# Logs: .logs/spec<spec>-<step>.log. The timings it reads are .logs/<ticket>-steps.tsv of scripts/gate-ticket.sh.
set -uo pipefail
if [ $# -ne 1 ]; then
  echo "usage: scripts/gate-spec.sh <spec>" >&2
  exit 2
fi
spec=$1
cd "$(dirname "$0")/.." || exit 2
mkdir -p .logs

# Slowest ticket-gate step 1 (verify, target 60 s); a time over the target calls for the fallback ADR-0014 and
# ADR-0020 name, by a finding (ADR-0023, F-17).
slowest() { # <n> <step> <target seconds>
  local best=""
  best=$(awk -F'\t' -v step="$2" 'FNR == 1 { t = FILENAME; sub(/.*\//, "", t); sub(/-steps\.tsv$/, "", t) }
    $1 == step && ($2 + 0) > max { max = $2 + 0; who = t } END { if (who != "") printf "%s %.1f", who, max / 1000 }' .logs/*-steps.tsv 2>/dev/null)
  if [ -z "$best" ]; then
    echo "slowest step $1 ($2): no timing in .logs/"
    return
  fi
  local ticket=${best% *} secs=${best#* }
  if awk -v s="$secs" -v t="$3" 'BEGIN { exit !(s > t) }'; then
    echo "slowest step $1 ($2): $ticket $secs s, over target $3 s: apply the fallback of ADR-0014/ADR-0020 by a finding"
  else
    echo "slowest step $1 ($2): $ticket $secs s (target $3 s)"
  fi
}
slowest 1 verify 60

now_ms() { date +%s%3N; }
: > ".logs/spec$spec-steps.tsv"
step() { # <name> <show> <command…>
  local name=$1 show=$2 started rc
  shift 2
  echo "== $name: $*"
  started=$(now_ms)
  "$@" > ".logs/spec$spec-$name.log" 2>&1
  rc=$?
  printf '%s\t%s\t%s\n' "$name" "$(( $(now_ms) - started ))" "$rc" >> ".logs/spec$spec-steps.tsv"
  if [ "$show" = 1 ] || [ "$rc" -ne 0 ]; then tail -n 40 ".logs/spec$spec-$name.log"; fi
  if [ "$rc" -ne 0 ]; then
    echo "gate-spec: step $name failed with exit code $rc (log .logs/spec$spec-$name.log)" >&2
    exit "$rc"
  fi
}

classc=$(awk -F'\t' 'NR > 1 && $2 == "C" { printf "%s%s", sep, $1; sep = "," }' quality/floors.tsv)

step clean-verify 0 ./mvnw -B clean verify -Pbench
step pit 0 ./mvnw -B -pl "$classc" -am test org.pitest:pitest-maven:mutationCoverage
step jmh 0 java -jar "${JMH_JAR:-bench/jmh/target/benchmarks.jar}" -prof gc -rf json -rff ".logs/spec$spec-jmh.json"
export RATCHET_JMH=".logs/spec$spec-jmh.json"
step regen 1 java scripts/ratchet.java regen --all
echo "gate-spec: $spec passed"
