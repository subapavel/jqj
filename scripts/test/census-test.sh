#!/usr/bin/env bash
# Script test for scripts/census.java (#57): the rules on a fixture of cases with a known closure, the checked-in
# list reproduced from jq-ref, every case assigned, and the spec-gate check. Needs Java 25 and jq-ref.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
tool=$repo/scripts/census.java
list=$repo/oracle/src/test/resources/census/jqtest.tsv
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -q -- "$2" "$3"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in $3:"; sed 's/^/     /' "$3"; fails=1; fi; }
# The spec and ticket of the case at <file>:<line> of a census list.
at() { awk -F'\t' -v l="$2" '!/^#/ && $4 == l { print $2 " " $3 }' "$1"; }

# --- the rules, on cases with a known closure ---
fx=$tmp/tests
mkdir -p "$fx/modules"
echo 'def a: "a";' > "$fx/modules/a.jq"
# One case per paragraph: program, input, expected. The line of each program is its handle below.
cat > "$fx/x.test" <<'CASES'
.
null
null

.a.b|.c
null
null

[.[] | .+1]
[1]
[2]

1 + 2
null
3

"\(.)", 1
1
"1"

map(.)
[1]
[1]

select(.)
2
2

sort_by(.a)
[]
[]

flatten
[[1]]
[1]

error("x")
null

$__loc__
null
{"file":"<top-level>","line":1}

reduce .[] as $x (0; $x)
[1]
1

{if: 1}
null
{"if":1}

..
1
1

range(0; 10; 3)
null
0

def f: if . > 0 then . - 1 | f else . end; f
1
0

path(.a)
null
["a"]

.a |= 1
null
{"a":1}

test("a")
"a"
true

@base64
"a"
"YQ=="

input
null

$ENV.PAGER
null
null

env | type
null
"object"

import "a" as foo; foo::a
null
"a"

%%FAIL
{(0):1}
jq: error: Cannot use number (0) as object key at <top-level>, line 1, column 3:

%%FAIL
{
jq: error: syntax error, unexpected end of file at <top-level>, line 1, column 1:

%%FAIL
. as $foo | break $foo
jq: error: $*label-foo is not defined at <top-level>, line 1, column 13:

%%FAIL
include "a" []; 0
jq: error: Module metadata must be an object at <top-level>, line 1, column 13:

format(.f)
{"f":"text"}
"{\"f\":\"text\"}"

def map(f): f; map(1)
null
1

def range(a; b; c): a; [range(0; 2)] | map(.)
null
[0,1]

%%FAIL IGNORE MSG
. as [] | null
jq: error: any text

"\((.), 1)"
null
"null"

. # reduce
null
null

"ab"[0:1]
null
"a"

(.)[0]
[1]
1

.a?[0]
null
null

def f: def g: f; g; f
null
null

def empty: 1; [empty]
null
[1]

[.[]]
[1]
[1]

1 + 2 == 3
null
true

1, 2
null
1
2

length, sort
[1]
1
[1]
CASES
out=$tmp/fixture.tsv
CENSUS_TESTS=$fx java "$tool" write "$out" > "$tmp/write.log" 2>&1
check "fixture: write" "$?" 0
check "identity: nothing to build after spec 1" "$(at "$out" x.test:1)" "1 -"
check "field chain and pipe: spec 1" "$(at "$out" x.test:5)" "1 -"
check "construction, iteration, arithmetic: the arithmetic ticket" "$(at "$out" x.test:9)" "2a S2a.15"
check "a folded 1 + 2 still needs arithmetic" "$(at "$out" x.test:13)" "2a S2a.15"
check "interpolation is not arithmetic; the comma after it counts" "$(at "$out" x.test:17)" "2a S2a.13"
check "map: the prelude" "$(at "$out" x.test:21)" "2a S2a.21"
check "select reaches the native empty" "$(at "$out" x.test:25)" "2a S2a.22"
check "sort_by reaches _sort_by_impl" "$(at "$out" x.test:29)" "2a S2a.23"
check "flatten is a native: its recursive _flatten is not reached" "$(at "$out" x.test:33)" "2a S2a.22"
check "error/1 belongs to control flow" "$(at "$out" x.test:37)" "2a S2a.18"
check "\$__loc__: variables" "$(at "$out" x.test:40)" "2a S2a.17"
check "reduce" "$(at "$out" x.test:44)" "2a S2a.19"
check "a keyword as an object key is not that keyword" "$(at "$out" x.test:48)" "2a S2a.14"
check ".. is recursion" "$(at "$out" x.test:52)" "2b -"
check "range/3 reaches the recursive while" "$(at "$out" x.test:56)" "2b -"
check "a recursive user definition" "$(at "$out" x.test:60)" "2b -"
check "path(f)" "$(at "$out" x.test:64)" "3 -"
check "an update operator" "$(at "$out" x.test:68)" "3 -"
check "regex" "$(at "$out" x.test:72)" "4 -"
check "a format" "$(at "$out" x.test:76)" "4 -"
check "input" "$(at "$out" x.test:80)" "5 -"
check "\$ENV" "$(at "$out" x.test:83)" "7 -"
check "env" "$(at "$out" x.test:87)" "7 -"
check "import" "$(at "$out" x.test:91)" "7 -"
check "%%FAIL constant object key: construction" "$(at "$out" x.test:95)" "2a S2a.14"
check "%%FAIL syntax error: the parser" "$(at "$out" x.test:99)" "2a S2a.12"
check "%%FAIL unknown label: variables and control flow meet at definitions" "$(at "$out" x.test:103)" "2a S2a.20"
check "%%FAIL module directive" "$(at "$out" x.test:107)" "7 -"
check "a computed format name" "$(at "$out" x.test:111)" "4 -"
check "a definition shadowing a builtin is the program's" "$(at "$out" x.test:115)" "2a S2a.20"
check "a shadowed name's builtin before another builtin is the prelude's; with range/2 it is .22, blocked by .19 since 2026-11-12" \
  "$(at "$out" x.test:119)" "2a S2a.22"
check "... and its native range/2 is needed" "$(awk -F'\t' '$4 == "x.test:119" { print $5 }' "$out")" \
  "S2a.11,S2a.14,S2a.19,S2a.20,S2a.21"
check "%%FAIL IGNORE MSG: the parser" "$(at "$out" x.test:123)" "2a S2a.12"
check "a parenthesis in an interpolation does not end it" "$(at "$out" x.test:127)" "2a S2a.13"
check "a comment is not code" "$(at "$out" x.test:131)" "1 -"
check "[ after a string indexes" "$(at "$out" x.test:135)" "2a S2a.13"
check "[ after a parenthesis indexes" "$(at "$out" x.test:139)" "2a S2a.13"
check "[ after ? indexes" "$(at "$out" x.test:143)" "2a S2a.13"
check "mutual recursion" "$(at "$out" x.test:147)" "2b -"
check "a definition named like a native is the program's" "$(at "$out" x.test:151)" "2a S2a.20"
check "needs .13 and .14: the earliest ticket blocked by both, .15" "$(at "$out" x.test:155)" "2a S2a.15"
check "... and why names both" "$(awk -F'\t' '$4 == "x.test:155" { print $6 }' "$out")" ".[] []"
check "needs .15 and .16: S2a.17, blocked by both since 2026-11-12" "$(at "$out" x.test:159)" "2a S2a.17"
check "needs .11 and .13: .13 follows .11, unchanged" "$(at "$out" x.test:163)" "2a S2a.13"
check "needs .22 and .23: neither follows the other, the sweep" "$(at "$out" x.test:168)" "2a S2a.25"
check "every fixture case has a line" "$(grep -vc '^#' "$out")" 43
has "why names the deciding builtin" "_match_impl" "$out"
CENSUS_TESTS=$fx java "$tool" verify "$out" > "$tmp/verify.log" 2>&1
check "fixture: the list is reproduced" "$?" 0
sed 's/^\(x\.test#[0-9a-f]*\)\t1\t/\1\t2b\t/' "$out" > "$tmp/changed.tsv"
CENSUS_TESTS=$fx java "$tool" verify "$tmp/changed.tsv" > "$tmp/verify.log" 2>&1
check "fixture: a changed list is a difference" "$?" 1
has "the difference is named" "differs from what jq-ref gives" "$tmp/verify.log"
mkdir -p "$tmp/bad"
printf '}\nnull\nnull\n' > "$tmp/bad/y.test"
CENSUS_TESTS=$tmp/bad java "$tool" write "$tmp/bad.tsv" > "$tmp/bad.log" 2>&1
check "a case jq-ref does not compile fails the census" "$?" 1
has "the case is named" "y.test:1" "$tmp/bad.log"
CENSUS_TESTS=$fx CENSUS_JQ=$tmp/no-such-jq java "$tool" write "$tmp/bad.tsv" > "$tmp/bad.log" 2>&1
check "no jq-ref fails the census" "$?" 1
has "the missing jq-ref is named" "no-such-jq" "$tmp/bad.log"

# --- the checked-in list: reproducible from jq-ref, every case assigned ---
java "$tool" verify > "$tmp/verify.log" 2>&1
check "the checked-in list is what jq-ref gives" "$?" 0
has "verify says so" "census: the list matches" "$tmp/verify.log"
check "879 cases" "$(grep -vc '^#' "$list")" 879
check "every case has an owning spec" "$(awk -F'\t' '!/^#/ && $2 !~ /^(1|2a|2b|3|4|5|6|7)$/' "$list" | wc -l)" 0
check "every 2a case has a ticket" "$(awk -F'\t' '!/^#/ && $2 == "2a" && $3 !~ /^S2a\.[0-9]+$/' "$list" | wc -l)" 0
check "jq.test's bad escape is the lexer's (#61)" "$(at "$list" jq.test:62)" "2a S2a.11"
check "jq.test's BOM case is spec 1's" "$(awk -F'\t' '$1 == "jq.test#1ce903e38afb" { print $2 }' "$list")" 1
missing=0
while IFS=$'\t' read -r id _; do
  case "$id" in '#'*|'') continue ;; esac
  awk -F'\t' -v i="$id" '$1 == i { f = 1 } END { exit !f }' "$list" || missing=1
done < "$repo/ratchet/oracle/jqtest.txt"
check "every ratcheted id is in the list: the ids are the jqtest oracle's" "$missing" 0
java "$tool" counts > "$tmp/counts.log" 2>&1
check "counts" "$?" 0
has "counts give the total" "total 879" "$tmp/counts.log"

# --- the spec-gate check ---
g=$tmp/gate
mkdir -p "$g/ratchet/oracle" "$g/oracle/src/test/resources/census"
printf '# census\njq.test#a\t2a\tS2a.11\tjq.test:1\tS2a.11\tliteral\njq.test#b\t2a\tS2a.13\tjq.test:5\tS2a.13\t,\njq.test#c\t4\t-\tjq.test:9\t-\ttest\n' > "$g/oracle/src/test/resources/census/jqtest.tsv"
printf 'jq.test#a\tinterpreter\njq.test#b\tinterpreter\n' > "$g/ratchet/oracle/jqtest.txt"
CENSUS_ROOT=$g java "$tool" gate 56 > "$tmp/gate.log" 2>&1
check "gate: every 2a case ratcheted" "$?" 0
printf 'jq.test#a\tinterpreter\n' > "$g/ratchet/oracle/jqtest.txt"
CENSUS_ROOT=$g java "$tool" gate 56 > "$tmp/gate.log" 2>&1
check "gate: a 2a case missing from the ratchet fails" "$?" 1
has "gate names the missing case" "jq.test#b" "$tmp/gate.log"
CENSUS_ROOT=$g java "$tool" gate 99 > "$tmp/gate.log" 2>&1
check "gate: a spec issue the census does not know fails" "$?" 1
has "gate says which issue" "#99" "$tmp/gate.log"
CENSUS_ROOT=$g java "$tool" gate > "$tmp/gate.log" 2>&1
check "gate: usage" "$?" 2
rm "$g/oracle/src/test/resources/census/jqtest.tsv"
CENSUS_ROOT=$g java "$tool" gate 56 > "$tmp/gate.log" 2>&1
check "gate: no census list fails" "$?" 1
has "gate says the list is missing" "no census list" "$tmp/gate.log"

exit $fails
