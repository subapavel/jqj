#!/usr/bin/env bash
# PROTOTYPE: runs every Spike A measurement; one JVM per (variant, filter). Output to stdout.
set -u
cd "$(dirname "$0")"
javac -d out src/Spike.java || exit 1
J="java -Xms2g -Xmx2g -cp out"
F="tail each mutual nontail"
SIZES=1000,10000,100000,1000000
echo "## cold first run, n=1000 and n=10000 (fresh JVM each)"
for v in stack tailloop; do for f in $F; do for n in 1000 10000; do $J -Dvariant=$v Spike cold $f $n; done; done; done
echo "## time (after 20 warm-up runs at n=1000; 5 reps per n)"
for f in $F; do
  $J -Dvariant=stack Spike time $f $SIZES 5
  $J -Dvariant=tailloop Spike time $f $SIZES 5
  $J -Dvariant=tramp -Dbounce=0 Spike time $f $SIZES 5
  $J -Dvariant=tramp -Dbounce=64 Spike time $f $SIZES 5
  $J -Dvariant=bigstack -Dstack=$((1<<30)) Spike time $f $SIZES 5
  $J -Dvariant=virtual Spike time $f $SIZES 5
done
echo "## max depth after warm-up"
for f in $F; do
  $J -Dvariant=stack Spike maxdepth $f 2000000
  $J -Dvariant=virtual Spike maxdepth $f 2000000
  for s in $((64<<20)) $((256<<20)) $((1<<30)); do $J -Dvariant=bigstack -Dstack=$s Spike maxdepth $f 4000000; done
done
