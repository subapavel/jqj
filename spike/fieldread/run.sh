#!/usr/bin/env bash
# PROTOTYPE: fetch JMH 1.37 jars, build, write the documents, check the variants, run JMH and jq-ref.
set -eu
cd "$(dirname "$0")"
mkdir -p lib
for a in org.openjdk.jmh:jmh-core:1.37 org.openjdk.jmh:jmh-generator-annprocess:1.37 net.sf.jopt-simple:jopt-simple:5.0.4 org.apache.commons:commons-math3:3.6.1; do
  mvn -q dependency:copy -Dartifact=$a -DoutputDirectory=lib
done
CP="lib/jmh-core-1.37.jar:lib/jmh-generator-annprocess-1.37.jar:lib/jopt-simple-5.0.4.jar:lib/commons-math3-3.6.1.jar"
rm -rf out; javac -proc:full -cp $CP -d out src/spike/*.java
java -cp out spike.Check data
java -cp out:$CP org.openjdk.jmh.Main FieldReadBench -prof gc -rf csv -rff results/4-jmh.csv
python3 table.py results/4-jmh.csv
python3 jqref.py
