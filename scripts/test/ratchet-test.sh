#!/usr/bin/env bash
# Script test for scripts/ratchet.java (#25, ADR-0020). Throwaway repo root per case, set through RATCHET_ROOT.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -q -- "$2" "$tmp/out"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in:"; sed 's/^/     /' "$tmp/out"; fails=1; fi; }

T=$'\t'
n=0
fresh() { n=$((n + 1)); root=$tmp/r$n; mkdir -p "$root/ratchet/oracle" "$root/oracle/target/oracle-reports" "$root/quality"
  printf 'module\tclass\tlines\tbranches\tmutation\nquery-core\tC\t90\t80\t80\njq-cli\t-\t80\t-\t-\n' > "$root/quality/floors.tsv"; }
run() { RATCHET_ROOT=$root RATCHET_MACHINE=m1 RATCHET_JMH=$root/jmh.json java "$tool" "$@" > "$tmp/out" 2>&1; echo $?; }
report() { printf '%b' "$2" > "$root/oracle/target/oracle-reports/$1.tsv"; }

# --- oracle pass list: check ---
fresh
printf 'a#1\tinterp\nb#2\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}PASS${T}\nc#3${T}FAIL${T}x\n"
check "all listed pass" "$(run check)" 0
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}FAIL${T}boom\n"
check "listed case fails" "$(run check)" 1
has "names the failing id" 'b#2'
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}STALE${T}\n"
check "listed case stale" "$(run check)" 1
report jqtest-interp "a#1${T}PASS${T}\n"
check "listed case missing from report" "$(run check)" 1
has "missing named" 'missing'

# --- drop a pass-list line -> that is not a failure of check, but regen records a rise on add ---
fresh
printf 'a#1\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}PASS${T}\n"
check "unlisted passing case is fine" "$(run check)" 0
check "regen oracles" "$(run regen --oracles)" 0
has "regen reports the rise" '+1'
check "regen wrote the new id" "$(printf 'a#1\tinterp\nb#2\tinterp\n' | cmp - "$root/ratchet/oracle/jqtest.txt"; echo $?)" 0
report jqtest-interp "a#1${T}FAIL${T}\nb#2${T}PASS${T}\n"
check "regen refuses a drop" "$(run regen --oracles)" 1
check "refused regen leaves the file" "$(printf 'a#1\tinterp\nb#2\tinterp\n' | cmp - "$root/ratchet/oracle/jqtest.txt"; echo $?)" 0

# --- retirement of a missing id when the source sha changed ---
fresh
mkdir -p "$root/src"; echo changed > "$root/src/jq.test"
printf '# source src/jq.test sha256 0000\njq.test#abc\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp ""
check "missing id retired when source sha differs" "$(run check)" 0
has "retirement warns" 'warning'
fresh
mkdir -p "$root/src"; echo same > "$root/src/jq.test"
sha=$(sha256sum "$root/src/jq.test" | cut -d' ' -f1)
printf '# source src/jq.test sha256 %s\njq.test#abc\tinterp\n' "$sha" > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp ""
check "missing id fails when source sha equal" "$(run check)" 1

# --- def column ---
fresh
printf 'x\td1\tinterp\n' > "$root/ratchet/oracle/differential.txt"
report differential-interp "x${T}PASS${T}${T}d1\n"
check "def equal" "$(run check)" 0
report differential-interp "x${T}PASS${T}${T}d2\n"
check "def changed" "$(run check)" 1
has "def named" 'def'

# --- compile.txt mode column ---
fresh
printf 'x\td1\tconservative\nx\td1\tcopy-only\n' > "$root/ratchet/oracle/compile.txt"
report compile-conservative "x${T}PASS${T}${T}d1\n"
report compile-copy-only "x${T}FAIL${T}${T}d1\n"
check "compile mode lines checked per mode" "$(run check)" 1
has "failing mode named" 'copy-only'

# --- coverage and floors ---
fresh
printf 'module\tlines %%\tbranches %%\nquery-core\t92\t85\n' > "$root/ratchet/coverage.tsv"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\noracle/query-core,p,A,0,0,15,85,8,92,0,0,0,0\n' > "$tmp/agg.csv"
mkdir -p "$root/oracle/target/site/jacoco-aggregate"; cp "$tmp/agg.csv" "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "coverage at its line" "$(run check)" 0
sed -i 's/,8,92,/,9,91,/' "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "lines below the ratchet" "$(run check)" 1
has "coverage named" 'query-core'
printf 'module\tlines %%\tbranches %%\nquery-core\t80\t70\n' > "$root/ratchet/coverage.tsv"
sed -i 's/,9,91,/,11,89,/;s/,15,85,/,25,75,/' "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "branches below the floor, above the ratchet" "$(run check)" 1
has "floor named" 'floor'
fresh
printf 'module\tlines %%\tbranches %%\nquery-core\t92\t85\n' > "$root/ratchet/coverage.tsv"
check "module not built is NOT_RUN, not a failure" "$(run check)" 0
has "NOT_RUN printed" 'NOT_RUN'

# --- mutation ---
fresh
printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t10\t100\n' > "$root/ratchet/mutation.tsv"
mkdir -p "$root/query-core/target/pit-reports"
pit() { { echo "<mutations>"; for i in $(seq 1 "$1"); do echo "<mutation detected='true' status='KILLED'/>"; done
  for i in $(seq 1 "$2"); do echo "<mutation detected='false' status='SURVIVED'/>"; done; echo "</mutations>"; } > "$root/query-core/target/pit-reports/mutations.xml"; }
pit 90 10
check "mutation at its line" "$(run check)" 0
pit 85 15
check "mutation score dropped" "$(run check)" 1
pit 70 30
printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t30\t100\n' > "$root/ratchet/mutation.tsv"
check "mutation under floor 80" "$(run check)" 1
pit 90 10
check "floor-mutation passes" "$(run floor-mutation query-core)" 0
pit 70 30
check "floor-mutation fails under floor" "$(run floor-mutation query-core)" 1

# --- bench: empty tolerance skipped ---
fresh
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t\n' > "$root/ratchet/bench.tsv"
echo '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":900.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "empty tolerance skipped by check" "$(run check)" 0
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t10\n' > "$root/ratchet/bench.tsv"
check "tolerance set, slower beyond it" "$(run check)" 1
echo '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":105.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "tolerance set, within it" "$(run check)" 0

# --- regen --all ---
fresh
printf 'module\tlines %%\tbranches %%\nquery-core\t92\t85\n' > "$root/ratchet/coverage.tsv"
check "regen --all requires every report" "$(run regen --all)" 1
has "missing report named" 'query-core'
mkdir -p "$root/oracle/target/site/jacoco-aggregate" "$root/jq-cli/target/site/jacoco" "$root/query-core/target/pit-reports"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\noracle/query-core,p,A,0,0,10,90,5,95\n' > "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\nj,p,A,0,0,0,0,10,90\n' > "$root/jq-cli/target/site/jacoco/jacoco.csv"
pit 95 5
printf '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":50.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t\n' > "$root/ratchet/bench.tsv"
check "regen --all with every report" "$(run regen --all)" 0
check "coverage rose" "$(printf 'module\tlines %%\tbranches %%\njq-cli\t90\t100\nquery-core\t95\t90\n' | cmp - "$root/ratchet/coverage.tsv"; echo $?)" 0
check "mutation written" "$(printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t5\t100\n' | cmp - "$root/ratchet/mutation.tsv"; echo $?)" 0
check "empty tolerance overwritten" "$(printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t50\tms/op\t\n' | cmp - "$root/ratchet/bench.tsv"; echo $?)" 0
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\noracle/query-core,p,A,0,0,10,90,9,91\n' > "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "regen --all refuses a coverage drop" "$(run regen --all)" 1

echo "usage error"
check "no arguments" "$(run)" 2
check "unknown command" "$(run frobnicate)" 2

exit $fails
