#!/usr/bin/env python3
"""PROTOTYPE: time `jq-ref -c .target` on each document (best of REPS, whole process) and max RSS."""
import os, subprocess, time
JQ = "/usr/local/bin/jq-ref"; REPS = 5
def best(args):
    b = 1e9
    for _ in range(REPS):
        t = time.perf_counter(); subprocess.run(args, capture_output=True, check=True); b = min(b, time.perf_counter() - t)
    return b * 1000
def rss(args):
    p = subprocess.Popen(args, stdout=subprocess.DEVNULL); _, _, ru = os.wait4(p.pid, 0); return ru.ru_maxrss / 1024
print(f"startup (jq-ref -n 1): {best([JQ, '-n', '1']):.2f} ms")
for s in ("1KB", "100KB", "10MB"):
    for p in ("start", "middle", "end"):
        a = [JQ, "-c", ".target", f"data/{s}-{p}.json"]
        print(f"{s:6} {p:7} best_of_{REPS}={best(a):9.2f} ms maxrss={rss(a):7.1f} MB")
