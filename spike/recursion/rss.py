#!/usr/bin/env python3
"""PROTOTYPE: run one command, print its stdout and the child's max RSS."""
import os, sys, subprocess
p = subprocess.Popen(sys.argv[1:], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
out = p.stdout.read().decode().strip()
_, _, ru = os.wait4(p.pid, 0)
print(f"{out}   maxrss={ru.ru_maxrss/1024:.0f} MB")
