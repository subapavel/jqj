#!/usr/bin/env python3
"""PROTOTYPE: JMH csv -> one line per document: time (us/op ± error) and bytes allocated per read."""
import csv, sys
t = {}; a = {}
for r in csv.DictReader(open(sys.argv[1])):
    name = r['Benchmark'].removeprefix('spike.FieldReadBench.')
    bench, _, metric = name.partition(':')
    key = (bench, r['Param: size'], r['Param: pos'])
    if metric == 'gc.alloc.rate.norm': a[key] = float(r['Score'])
    elif metric == '': t[key] = (float(r['Score']), float(r['Score Error (99.9%)']))
V = ('tapeReused', 'tapeFresh', 'validateOnly', 'skipAll', 'stopFirst')
print('| document | ' + ' | '.join(V) + ' |'); print('| --- ' * (len(V) + 1) + '|')
for s in ('1KB', '100KB', '10MB'):
    for p in ('start', 'middle', 'end'):
        print(f'| {s} {p} | ' + ' | '.join(f'{t[(v,s,p)][0]:.2f} ± {t[(v,s,p)][1]:.2f} µs, {a[(v,s,p)]:.0f} B' for v in V) + ' |')
