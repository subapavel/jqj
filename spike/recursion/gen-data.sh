#!/usr/bin/env bash
# PROTOTYPE: writes data/n<N>.json = [0,1,...,N-1] for the jq-ref runs (jqref.py).
cd "$(dirname "$0")"; mkdir -p data
for n in 1000 10000 100000 1000000; do /usr/local/bin/jq-ref -nc "[range($n)]" > data/n$n.json; done
