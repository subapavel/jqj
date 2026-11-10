#!/usr/bin/env python3
"""PROTOTYPE: time the reference jq on the four spike filters. Wall time and max RSS per run."""
import os, subprocess, sys, time
JQ = "/usr/local/bin/jq-ref"
REPS = int(os.environ.get("REPS", "3"))
def run(f, n):
    best = None
    for _ in range(REPS):
        t0 = time.perf_counter()
        p = subprocess.Popen([JQ, "-f", f"filters/{f}.jq", f"data/n{n}.json"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = p.communicate(timeout=600)
        _, status, ru = os.wait4(p.pid, 0) if False else (None, p.returncode, None)
        dt = time.perf_counter() - t0
        best = dt if best is None or dt < best else best
    return p.returncode, out.decode().strip()[:40], err.decode().strip()[:120], best
def rss(f, n):
    pid = os.fork()
    if pid == 0:
        fd = os.open(os.devnull, os.O_WRONLY); os.dup2(fd, 1); os.dup2(fd, 2)
        os.execv(JQ, [JQ, "-f", f"filters/{f}.jq", f"data/n{n}.json"])
    _, _, ru = os.wait4(pid, 0)
    return ru.ru_maxrss
for f in sys.argv[1].split(","):
    for n in sys.argv[2].split(","):
        rc, out, err, dt = run(f, n)
        print(f"{f:8} n={n:>8} exit={rc} best_of_{REPS}={dt*1000:9.1f} ms maxrss={rss(f, n)/1024:8.1f} MB out={out} err={err}", flush=True)
