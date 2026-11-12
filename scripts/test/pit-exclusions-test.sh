#!/usr/bin/env bash
# Script test for the PIT test exclusions (#47, ADR-0014, ADR-0020). Each module keeps its excludedTestClasses in its
# own POM and nowhere else: a -DexcludedTestClasses on the command line replaces every module's POM list instead of
# adding to it (observed with -X, #45). Needs Java 25 and the Maven wrapper (help:effective-pom).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$here/../..
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# --- no command-line list in the scripts that run PIT ---
for f in scripts/gate-spec.sh .github/workflows/nightly.yml; do
  check "no excludedTestClasses property in $f" "$(grep -c 'excludedTestClasses' "$repo/$f")" 0
done

# --- each module's effective list ---
# jq-cli: the module-path tests and the launcher test need surefire executions PIT does not run (jq-cli/pom.xml, #38).
# oracle: the Survey tests fail only on CRASH and stay out of PIT (ADR-0020).
expected() {
  case $1 in
    query-core | jq-core) echo "" ;;
    jq-cli) echo "io.github.subapavel.jqj.cli.*ModulePathTest,io.github.subapavel.jqj.cli.LauncherTest" ;;
    oracle) echo "io.github.subapavel.jqj.oracle.diff.DifferentialSurveyTest,io.github.subapavel.jqj.oracle.jqtest.JqTestSurveyTest,io.github.subapavel.jqj.oracle.json.JsonSurveyTest" ;;
  esac
}
modules="query-core jq-core jq-cli oracle"
if ! "$repo/mvnw" -B -q -f "$repo/pom.xml" -pl "${modules// /,}" \
    org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom "-Doutput=$tmp/effective.xml" > "$tmp/mvn.log" 2>&1 < /dev/null; then
  echo "FAIL help:effective-pom:"; sed 's/^/     /' "$tmp/mvn.log"
  exit 1
fi
# <module>\t<sorted, comma-joined excludedTestClasses params> for every project of the effective POM
awk '
  /<project[ >]/ { name = ""; parent = 0 }
  /<parent>/ { parent = 1 }
  /<\/parent>/ { parent = 0 }
  !parent && name == "" && /<artifactId>/ { name = $0; gsub(/.*<artifactId>|<\/artifactId>.*/, "", name); print name "\t" }
  /<excludedTestClasses>/ { inside = 1; next }
  /<\/excludedTestClasses>/ { inside = 0 }
  inside && /<param>/ { p = $0; gsub(/.*<param>|<\/param>.*/, "", p); print name "\t" p }
' "$tmp/effective.xml" > "$tmp/params"
for m in $modules; do
  check "$m is in the effective POM" "$(grep -c "^$m	\$" "$tmp/params")" 1
  got=$(awk -F'\t' -v m="$m" '$1 == m && $2 != "" { print $2 }' "$tmp/params" | LC_ALL=C sort | paste -sd, -)
  check "$m effective excludedTestClasses" "$got" "$(expected "$m")"
done

exit $fails
