# Recursion: weighted stack units, a resumable trampoline only in the interpreter

Status: proposed (2026-11-10, issue #7 "What is the execution protocol?"); details the hybrid chosen on issue #3

Tail calls are jumps. Other recursive calls run on the Java stack until the stack units reach a
limit U, and beyond U they run on a CPS trampoline. A memory-bounded recursion-depth budget limits
the depth. `StackOverflowError` is only a backstop.

- **Tail calls:** `TAIL_SELF` is a loop that reuses the frame. `TAIL_GROUP` is a call loop, and the
  bytecode backend emits one method per group with an `int` switch on the function, under the
  method-size limit.
- **Stack units:** each `RECURSIVE` call and each real-closure `ParamCall` adds `w(site)` units on
  entry and takes them off on return. `w(site)` is the number of Java frames the backend nests
  from the callee's entry to the site, fixed when the tree is built. Starting a trampoline also
  adds units. At `units ≥ U` the call runs on the trampoline.
- **The trampoline is resumable.** The driver stops at each result and returns it to the call that
  started it. That call emits the result from its own frame, then resumes the driver, so only one
  driver is live per starter. Spike A's extension (`spike/recursion-depth` `0c6bffe`) measured
  both forms on a recursion of depth n whose continuation starts a second recursion of depth 1000
  at every level, with limit 256, a 1 MB stack and a cold JVM:
  - **Sink called inside the driver:** 201 nested drivers, then `StackOverflowError`, at n = 2000
    and at n = 10 000.
  - **Driver returning each result:** at most one live driver; n = 2000 took 954 ms and
    n = 10 000 took 3390 ms.
  - **Cost of returning each result:** on the plain non-tail shape the medians were within the
    noise up to about 1.3× (at 10⁶: 392 → 517 ms, with ranges 301–559 and 267–605 ms).
  - Numbers are in `bench/RESULTS.md`.
- **U is a constant per release,** set by a calibration test. The test runs cold and warm, on
  stacks of 512 KB and one other size, on both backends and on a virtual thread, over the T, E, M
  and N shapes, a wide body, a closure chain, the nested-driver shape and path-mode `..` on a
  10 000-deep document.
  - A host may declare the stack bytes one evaluation may use. U is then derived after taking off
    a measured fixed reserve: this JVM keeps about 96 KiB of every stack free of frames, so U does
    not scale linearly.
  - The field belongs to "What are the public API, the host SPI and the sandbox?".
- **CPS lives only in the interpreter.** A compiled filter keeps the interpreter tree of its
  recursive groups and closure bodies. A bytecode call beyond U is handed to the interpreter with
  the function id, the input, the value arguments, the closure arguments, the label tokens and
  the caller's `Sink`. In TRACKED path mode it also passes the input's `Path` and the `PathSink`.
  - A closure argument is a `Filter` that exposes its IR node id and its captured values.
  - The speed of recursion beyond U at interpreter speed is not measured yet.
- **The recursion-depth budget** counts live activations, on the Java stack and on the trampoline
  together. Its default lets the 10⁶ levels that jq-ref runs complete. The number is set in "What
  are the public API, …".
- **The backstop:** `StackOverflowError` is caught only at the evaluation entry and becomes
  `Termination(STACK)` (ADR-0008).

## Considered options

- **A fixed K = 256 calls:** the stack per call varied about 5× by shape (2 030 to 11 780 levels
  warm on 1 MB), so a wide body overflows before K.
- **A U scaled linearly with the host's stack:** it overstates U on small stacks, because of the
  fixed reserve.
- **CPS also in bytecode:** fastest deep recursion, but it needs a second generated variant of
  every recursive group, doubled again for path mode.
- **A sink called inside the driver:** drivers nest without bound (measured above).
- **Recursion depth charged to the nesting-depth or bytes-built budget:** the reader stops at
  10 000 levels while recursion needs 10⁶, and frames are freed on return.
- **A big-stack thread beyond U:** it was faster on N (285 against 350 ms at 10⁶), but costs a
  thread per deep evaluation, the stack size is only a hint, and the owner chose the hybrid.
