#!/usr/bin/env bash
# Script test for scripts/gate-ticket.sh and scripts/gate-spec.sh (#25, ADR-0014). A throwaway repo holds copies of
# the gate scripts and ratchet.java, a stub mvnw that logs its arguments, stub check scripts, and a stub java for
# the JMH jar. STUB_FAIL=<word>:<rc> makes the stub mvnw or check script whose arguments contain <word> exit <rc>.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -q -- "$2" "$3"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in $3:"; sed 's/^/     /' "$3"; fails=1; fi; }
hasnt() { if grep -q -- "$2" "$3"; then echo "FAIL $1: '$2' found in $3"; fails=1; else echo "ok   $1"; fi; }

repo=$tmp/repo
mkdir -p "$repo/scripts" "$repo/quality" "$repo/ratchet" "$repo/stubbin"
g() { git -C "$repo" "$@"; }
cp "$here/../gate-ticket.sh" "$here/../gate-spec.sh" "$here/../ratchet.java" "$here/../census.java" "$repo/scripts/" 2>/dev/null
printf 'module\tclass\tlines\tbranches\tmutation\nquery-core\tC\t90\t80\t80\njq-core\tC\t90\t80\t80\njq-cli\t-\t80\t-\t-\n' > "$repo/quality/floors.tsv"
# a recording in the base commit that no later commit changes
mkdir -p "$repo/oracle/src/test/resources/json"
echo old > "$repo/oracle/src/test/resources/json/old.rec"

stub='#!/usr/bin/env bash
echo "$(basename "$0") $*" >> "$STUB_CALLS"
case "${STUB_FAIL:-}" in
  "") ;;
  *) w=${STUB_FAIL%%:*}; rc=${STUB_FAIL##*:}; case "$(basename "$0") $*" in *"$w"*) exit "$rc" ;; esac ;;
esac
exit 0'
printf '%s\n' "$stub" > "$repo/mvnw"
for s in check-lf-checkout check-trailers check-core-neutral oracle-record; do printf '%s\n' "$stub" > "$repo/scripts/$s.sh"; done
# stub java: the JMH jar writes its result file, everything else runs the real java
cat > "$repo/stubbin/java" <<JAVA
#!/usr/bin/env bash
if [ "\$1" = "-jar" ]; then
  echo "java \$*" >> "\$STUB_CALLS"
  case "\${STUB_FAIL:-}" in jmh:*) exit "\${STUB_FAIL##*:}" ;; esac
  while [ \$# -gt 0 ]; do [ "\$1" = "-rff" ] && echo '[]' > "\$2"; shift; done
  exit 0
fi
exec $(command -v java) "\$@"
JAVA
chmod +x "$repo/mvnw" "$repo/scripts/"*.sh "$repo/stubbin/java"

g init -q -b main
g config user.email a@b.c
g config user.name t
g add -A
g commit -q -m base
g branch base
mkdir -p "$repo/query-core/src/main/java/io/github/subapavel/jqj/query" "$repo/jq-core/src/main/java/io/github/subapavel/jqj/core"
echo 'class Foo {}' > "$repo/query-core/src/main/java/io/github/subapavel/jqj/query/Foo.java"
echo 'class Bar {}' > "$repo/jq-core/src/main/java/io/github/subapavel/jqj/core/Bar.java"
echo 'module x {}' > "$repo/query-core/src/main/java/module-info.java"
echo 'x' > "$repo/query-core/README.md"
g add -A
g commit -q -m change

export STUB_CALLS=$tmp/calls
export PATH=$repo/stubbin:$PATH
export RATCHET_MACHINE=m1
unset STUB_FAIL

ticket() { : > "$STUB_CALLS"; rm -rf "$repo/.logs"; (cd "$repo" && scripts/gate-ticket.sh "$@" > "$tmp/out" 2>&1; echo $?); }

check "ticket: usage" "$(ticket 25)" 2
check "ticket: green" "$(ticket 25 query-core,jq-core base)" 0
has "step 1 is the whole reactor with -Pbench" "mvnw -B -Pbench verify" "$STUB_CALLS"
# F-27: PIT left the ticket gate, even with changed classes in class-C modules
hasnt "no PIT call with changed query-core and jq-core classes" "pitest" "$STUB_CALLS"
hasnt "no class list for PIT" "targetClasses" "$STUB_CALLS"
check "mvnw runs only step 1" "$(grep -c '^mvnw ' "$STUB_CALLS")" 1
has "step 3 LF check" "check-lf-checkout.sh" "$STUB_CALLS"
has "step 3 trailer check on ref..HEAD" "check-trailers.sh base..HEAD" "$STUB_CALLS"
has "step 3 word check" "check-core-neutral.sh" "$STUB_CALLS"
check "log of step 1, no PIT log" "$(ls "$repo/.logs" | grep -c '^25-\(verify\|pit\|pit-floor\)\.log$')" 1
has "step 1 log written" "25-verify.log" "$(ls "$repo/.logs" > "$tmp/ls"; echo "$tmp/ls")"
has "step timings recorded" "^verify	" "$repo/.logs/25-steps.tsv"
hasnt "no pit timing recorded" "^pit" "$repo/.logs/25-steps.tsv"
order=$(grep -o 'mvnw -B -Pbench verify\|test org.pitest\|check-lf\|check-trailers\|check-core\|java ' "$STUB_CALLS" | tr '\n' ' ')
check "steps in ADR order" "$order" "mvnw -B -Pbench verify check-lf check-trailers check-core "

: > "$STUB_CALLS"; rm -rf "$repo/.logs"
(cd "$repo" && ORACLE_REQUIRE=/x/req.txt scripts/gate-ticket.sh 25 query-core base > "$tmp/out" 2>&1)
has "required case file passed to the oracle" "-Doracle.require=/x/req.txt" "$STUB_CALLS"

export STUB_FAIL=verify:7
check "ticket: step 1 fails, its exit code" "$(ticket 25 query-core base)" 7
hasnt "nothing after the failing step 1" "check-lf" "$STUB_CALLS"
has "failing step named" "verify" "$tmp/out"

# a failing PIT cannot stop the ticket gate: none runs (F-27)
export STUB_FAIL=pitest:9
check "ticket: no PIT to fail" "$(ticket 25 query-core base)" 0
hasnt "no PIT runs" "pitest" "$STUB_CALLS"
has "step 3 reached after step 1" "check-lf" "$STUB_CALLS"

export STUB_FAIL=check-trailers:4
check "ticket: step 3 trailer check fails" "$(ticket 25 query-core base)" 4
hasnt "ratchet not reached" "ratchet.java check" "$tmp/out"
unset STUB_FAIL

# no changed class: the gate runs as with changed classes
check "ticket: nothing to mutate" "$(ticket 25 query-core HEAD)" 0
hasnt "no PIT without changed classes" "pitest" "$STUB_CALLS"
hasnt "no PIT step announced" "== pit" "$tmp/out"

# step 4: the ratchet decides
printf 'jq.test#a\tinterp\n' > "$repo/ratchet/oracle-listed.tmp"
mkdir -p "$repo/ratchet/oracle"
printf 'jq.test#a\tinterp\n' > "$repo/ratchet/oracle/jqtest.txt"
check "ticket: step 4 ratchet fails" "$(ticket 25 query-core base)" 1
has "ratchet failure shown" "jq.test#a" "$tmp/out"
rm "$repo/ratchet/oracle/jqtest.txt" "$repo/ratchet/oracle-listed.tmp"

# step 3 record: the recordings changed since ref are verified (ADR-0020)
mkdir -p "$repo/oracle/src/test/resources/diff"
echo r > "$repo/oracle/src/test/resources/diff/x.rec"
g add -A
g commit -q -m rec
check "ticket: a changed recording" "$(ticket 25 query-core HEAD~1)" 0
has "the changed recording is verified" "oracle-record.sh --verify oracle/src/test/resources/diff/x.rec" "$STUB_CALLS"
hasnt "an unchanged recording is not verified by the ticket gate" "old.rec" "$STUB_CALLS"
check "ticket: no changed recording" "$(ticket 25 query-core HEAD)" 0
hasnt "no changed recording, no record call" "oracle-record" "$STUB_CALLS"
chmod -x "$repo/scripts/oracle-record.sh"
check "ticket: a recorder without its exec bit fails the gate" "$(ticket 25 query-core HEAD~1)" 126
has "the record step is named" "step record failed" "$tmp/out"
chmod +x "$repo/scripts/oracle-record.sh"

# --- spec gate ---
spec() { : > "$STUB_CALLS"; (cd "$repo" && scripts/gate-spec.sh "$@" > "$tmp/out" 2>&1; echo $?); }
check "spec: usage" "$(spec)" 2

rm -rf "$repo/.logs"; mkdir -p "$repo/.logs"
printf 'verify\t50000\t0\npit\t200000\t0\nlf\t100\t0\n' > "$repo/.logs/31-steps.tsv"
printf 'verify\t70000\t0\npit\t90000\t0\n' > "$repo/.logs/32-steps.tsv"
mkdir -p "$repo/bench/jmh/target"
# every report regen --all needs
mkdir -p "$repo/oracle/target/site/jacoco-aggregate" "$repo/jq-cli/target/site/jacoco" "$repo/query-core/target/pit-reports" "$repo/jq-core/target/pit-reports"
hdr=GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED
printf "%s\noracle/query-core,p,A,0,0,5,95,5,95\noracle/jq-core,p,B,0,0,5,95,5,95\n" "$hdr" > "$repo/oracle/target/site/jacoco-aggregate/jacoco.csv"
printf "%s\nj,p,C,0,0,0,0,5,95\n" "$hdr" > "$repo/jq-cli/target/site/jacoco/jacoco.csv"
for m in query-core jq-core; do echo "<mutations><mutation status='KILLED'/></mutations>" > "$repo/$m/target/pit-reports/mutations.xml"; done
# the census of #57: spec 1 (#22) has one case, ratcheted and passing
mkdir -p "$repo/oracle/src/test/resources/census" "$repo/oracle/target/oracle-reports" "$repo/ratchet/oracle"
printf '# census\njq.test#a\t1\t-\tjq.test:1\t-\t-\njq.test#c\t4\t-\tjq.test:9\t-\ttest\n' > "$repo/oracle/src/test/resources/census/jqtest.tsv"
printf 'jq.test#a\tinterpreter\n' > "$repo/ratchet/oracle/jqtest.txt"
printf 'jq.test#a\tPASS\t\n' > "$repo/oracle/target/oracle-reports/jqtest-interpreter.tsv"
check "spec: green" "$(spec 22)" 0
has "slowest step 1" "slowest step 1 (verify): 32 70.0 s" "$tmp/out"
has "step 1 over its target" "over target 60 s" "$tmp/out"
has "step 1 over its target goes to the owner (F-27)" "over target 60 s: report it to the owner in the spec PR (F-27)" "$tmp/out"
hasnt "no fallback named for step 1 (F-27)" "fallback" "$tmp/out"
hasnt "no ticket-gate PIT report (F-27)" "slowest step 2" "$tmp/out"
has "spec step 1 clean verify" "mvnw -B clean verify -Pbench" "$STUB_CALLS"
has "spec step 2 PIT on whole class-C modules and oracle's crossModule run (ADR-0014)" "mvnw -B -pl query-core,jq-core,oracle -am test org.pitest:pitest-maven:mutationCoverage" "$STUB_CALLS"
# The property replaces every module's POM list (observed, #45), so oracle's three Survey exclusions are repeated.
has "jq-cli's tests and oracle's Surveys kept out of PIT" "mutationCoverage -DexcludedTestClasses=io.github.subapavel.jqj.cli.LauncherTest,io.github.subapavel.jqj.cli.\*ModulePathTest,io.github.subapavel.jqj.oracle.diff.DifferentialSurveyTest,io.github.subapavel.jqj.oracle.json.JsonSurveyTest,io.github.subapavel.jqj.oracle.jqtest.JqTestSurveyTest$" "$STUB_CALLS"
has "spec verifies every recording (ADR-0020)" "oracle-record.sh --verify oracle/src/test/resources/diff/x.rec oracle/src/test/resources/json/old.rec" "$STUB_CALLS"
order=$(grep -o 'clean verify\|oracle-record\|mutationCoverage' "$STUB_CALLS" | tr '\n' ' ')
check "spec record step after clean-verify, before PIT" "$order" "clean verify oracle-record mutationCoverage "
has "spec step 3 JMH" "java -jar bench/jmh/target/benchmarks.jar -prof gc -rf json" "$STUB_CALLS"
has "spec step 4 regen --all" "regen: ok" "$tmp/out"
has "spec step 5 the census gate (#57)" "census gate: spec 1 (#22): 1 cases, 0 missing" "$tmp/out"
order=$(grep -o '== [a-z-]*' "$tmp/out" | tr '\n' ' ')
check "spec census step last" "$order" "== clean-verify == record == pit == jmh == regen == census "
has "spec logs" "regen" "$(ls "$repo/.logs" | tr '\n' ' ' > "$tmp/ls"; echo "$tmp/ls")"

printf 'verify\t50000\t0\n' > "$repo/.logs/31-steps.tsv"; rm "$repo/.logs/32-steps.tsv"
spec 22 > /dev/null
hasnt "no 'over target' within the target" "over target" "$tmp/out"

# a case the census gives the spec, missing from the ratchet, fails the spec gate (#57)
printf 'jq.test#b\t1\t-\tjq.test:5\t-\t-\n' >> "$repo/oracle/src/test/resources/census/jqtest.tsv"
check "spec: a census case missing from the ratchet fails" "$(spec 22)" 1
has "the missing case is named" "missing jq.test#b" "$tmp/out"
has "the failing step is the census" "step census failed" "$tmp/out"
printf '# census\njq.test#a\t1\t-\tjq.test:1\t-\t-\n' > "$repo/oracle/src/test/resources/census/jqtest.tsv"

export STUB_FAIL=clean:5
check "spec: step 1 fails, its exit code" "$(spec 22)" 5
hasnt "spec stops after step 1" "pitest" "$STUB_CALLS"
hasnt "no record step after a failed step 1" "oracle-record" "$STUB_CALLS"
export STUB_FAIL=oracle-record:3
check "spec: a recording differs, its exit code" "$(spec 22)" 3
hasnt "spec stops after the record step" "pitest" "$STUB_CALLS"
export STUB_FAIL=jmh:6
check "spec: JMH fails, its exit code" "$(spec 22)" 6
hasnt "regen not reached after JMH" "regen" "$tmp/out"
unset STUB_FAIL

rm -rf "$repo/oracle/target" "$repo/jq-cli/target"
# regen --all refuses without reports: the gate fails with ratchet's code
rm -rf "$repo/.logs"; mkdir -p "$repo/.logs"
check "spec: regen --all has no coverage report" "$(spec 22)" 1
has "regen failure shown" "no JaCoCo report" "$tmp/out"

exit $fails
