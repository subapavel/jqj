# jqj

Runs a jq filter over JSON bytes on the JVM: the filter is compiled once and then run on each
input without turning the input into an object tree.

Seed glossary from the founding conversation (2026-11-10). `/domain-modeling` sharpens it; a term
marked _(open)_ is waiting for a Phase 0 decision.

## Language

**Filter**:
A jq program: text that, given one input value, produces zero, one or many results.
_Avoid_: query, expression (for the whole program), script

**Result**:
One value a filter produces for one input.
_Avoid_: output (that is the bytes the writer produces), item

**Builtin**:
A function every filter can call without defining it.

**Prelude**:
The builtins that are themselves written in jq and loaded by the resolver.
_Avoid_: standard library, builtin.jq (except when naming jq's file)

**Native builtin**:
A builtin implemented in Java and called from the IR as an intrinsic.
_Avoid_: C builtin, primitive

**Reference jq**:
The pinned release of the original jq whose binary decides what a filter does.
_Avoid_: real jq, upstream jq, jq-ref (that is the binary's path)

**Deviation**:
A behaviour where jqj differs from the reference jq on purpose, listed in `DEVIATIONS.md` with
its reason.
_Avoid_: incompatibility, known issue

## Values

**Document**:
One JSON value held as its UTF-8 bytes plus its tape.
_Avoid_: message (Flottila's word), payload, tree

**Tape**:
The flat index of a document: one entry per JSON value, object keys included, giving its type and
where it lies in the bytes; a container's entry also says how many entries it holds, so the whole
container can be stepped over at once.
_Avoid_: index (alone), DOM, token stream

**View**:
A value that is a position in a document. It is valid only while its document is.
_Avoid_: reference, pointer, lazy value

**Stale view**:
A view whose document has been replaced by a later read. Using it is an error, never a wrong value.
_Avoid_: dangling view, expired value

**Built value**:
A value the filter constructed, independent of any document. Immutable once handed on.
_Avoid_: heap value, materialised value

**Detach**:
To copy a view into a built value so it can outlive its document.
_Avoid_: materialise, clone

**Shape**:
The shared key list of the objects built at one construction site.
_Avoid_: hidden class, schema

**Growable object**:
A built object whose keys are made at run time, so it keeps its own key list instead of a shape.
_Avoid_: dictionary, map object, hash object

**Ownership marker**:
What lets the engine update a built value in place: the mark that a running operation (`reduce`,
`foreach`, an update, an array builder) is the value's only holder. It is cleared when the value
escapes. _(open: the exact rule, ticket 8)_
_Avoid_: reference count, transient, lock

**Escape**:
The moment a built value becomes reachable from somewhere other than the operation that owns it:
bound to a variable, stored in another value, or emitted.

## Compiling

**IR**:
The one intermediate form of a filter that both backends read: one closed family of
format-neutral nodes and the functions they call. Everything a format adds reaches it as an
operation.
_Avoid_: AST (that is the parser's output), bytecode (that is jq's own or the JVM's)

**Operation**:
What an intrinsic IR node calls: a navigation step or a native builtin of one format, described by
the facts the analyses need (how many results, how it extends a path, what it keeps).
_Avoid_: primitive, opcode

**Navigation step**:
An operation that goes from a value to a part of it (field, index, slice, iterate). Only steps
extend a path.

**Closure**:
A filter argument of a jq function, run in the caller's scope each time the callee calls it.
_Avoid_: lambda, callback

**Recursive group**:
The functions that can reach each other through calls.

**Specialisation**:
Making a copy of a recursive function for one closure argument that every call in its group passes
on unchanged, so the closure disappears.

**Tail call**:
A call inside a recursive group with nothing left to do after it and at most one result in
progress to its left; it runs as a jump.

**Last read**:
The only read of a variable's value in its binding, after which the value may be moved instead of
copied.

**Path shape**:
How an update or `path()` runs: along a fixed path, while walking the value, or by tracking every
path.

**Hold**:
A place that keeps a value while later steps run (a variable, an accumulator, an array being
built, an error payload). A view of an input document is detached when it enters a hold that spans
a later read.
_Avoid_: pin, retain

**Root use**:
Whether a filter reads its input not at all, as the elements of one array one at a time, or whole.

**Analysis**:
A pass over the IR that proves a property a backend can use: cardinality, path shape, root use,
touched fields.

**Cardinality**:
How many results an IR node can produce for one input: none, exactly one, at most one, or many. An
error or a break is not a result.

**Backend**:
What turns IR into something that runs. There are two.

**Interpreter backend**:
The backend that turns each IR node into an object and runs the tree.
_Avoid_: tier 0, tree walker

**Bytecode backend**:
The backend that generates one JVM class per filter with the Class-File API.
_Avoid_: compiler (the whole pipeline is the compiler), JIT (that is HotSpot's)

**Compiled filter**:
The immutable, thread-safe result of compiling a filter, whichever backend made it.
_Avoid_: program, plan

## Running

**Emit**:
To hand one result to the next stage. The answer says whether to keep going.
_Avoid_: yield, return, push (as a noun for the call)

**Sink**:
What receives emitted results.

**Path mode**:
Running a filter's second variant, in which each result comes with the path that led to it or is
untracked, as `path()`, `paths` and updates need.

**Untracked**:
A path-mode result with no path. Used as a path, it raises "Invalid path expression".

**Stop**:
An emit answered with false: the producer emits nothing more and returns.
_Avoid_: cancel, abort

**Label token**:
The number one activation of a `label` gets. Its breaks hand it back, and they are no error.
_Avoid_: label name

**Catch set**:
The errors a node turns into a run of its handler. A break and a termination are in none.

**Termination**:
An end of the evaluation that no filter can catch: halt, a budget, a stale view or the stack
backstop.
_Avoid_: fatal error

**Tick**:
One count against the step budget: at a site the IR defines, or per element or KiB inside a native
loop.

**Stack units**:
The Java stack a call is charged for. A call over the limit runs on the trampoline.

**Trampoline**:
The interpreter's resumable loop that runs recursion beyond the stack-unit limit on the heap. It
returns each result to the call that started it.

**Workspace**:
The reusable scratch memory of one evaluation (tape array, output buffer, path stack), passed in
by the caller and never shared between two running evaluations.
_Avoid_: context (that word is taken by jq's `.`), thread-local, arena (that is the JDK's)

**Budget**:
A hard limit on one evaluation: nesting depth, recursion depth, bytes built, bytes written, steps,
or time. Going over is a termination that carries a jq error message.
_Avoid_: quota, timeout (one budget among several)

**Automatic streaming**:
Running a filter of the form `<path>[] | rest` element by element, without holding the whole
document, when `rest` never looks back at it.
_Avoid_: `--stream` (that is jq's event mode, a different thing)

**Host**:
The program embedding the engine. It supplies the clock, the environment policy, the module
loader for `import`, the debug sink and extra inputs.
_Avoid_: runtime, container, platform

**Sandbox defaults**:
What a filter may not do when embedded unless the host allows it: read the environment, name a
file, halt, import from disk.

## Verification

**Oracle**:
An independent source of the expected output for a case: jq's own test files, the reference jq
binary, the other backend, or a JSON conformance suite.

**Case**:
One oracle entry with a stable id: a filter, an input, flags and the expected outputs.
_Avoid_: test (that is a JUnit method), example

**Differential oracle**:
Running a case in jqj and in the reference jq binary and requiring byte-identical output.

**Backend oracle**:
Running a case in both backends and requiring identical output.

**Ratchet**:
A committed record of what passes (oracle cases, coverage, mutation scores, benchmark
baselines). Merges into `main` may only keep or raise it; the verifier regenerates it once per
spec, nobody edits it by hand.

**Floor**:
A fixed minimum of coverage or mutation score below which the build fails, whatever the ratchet
says.

**Benchmark scenario**:
One measured quantity of one filter on one input size, such as the throughput of field extract on
a 10 MB document. Its baseline is a line of the ratchet.

**Spike**:
A throwaway experiment that measures one risky assumption before a decision is made. Its code is
never merged; its numbers go to `bench/RESULTS.md`.
_Avoid_: prototype (in this repo that word names the wayfinder ticket type), proof of concept

## Way of working

**Spec**:
A GitHub issue (`spec`) describing one deliverable from the user's side, parent of its tickets.

**Ticket**:
A tracer-bullet slice of a spec, sized for one fresh context, with blocking edges and one class.

**Seam**:
An outer interface of a part of the system where a test gives input and checks output without
knowing the inside. A spec names its seams before its tickets exist.
_Avoid_: test boundary, integration point

**Frontier**:
The open, unblocked, unclaimed tickets of a spec or map.

**Integration branch**:
`spec/<issue>-<slug>`, where a spec's tickets are merged one by one before one PR to `main`.

**Ticket gate**:
The automatic checks the verifier runs on one ticket's worktree before it merges: the scoped
build of the touched modules, a compile of the whole build, and the ratchet check.
_Avoid_: CI (that runs the spec gate's checks), pre-merge review

**Spec gate**:
The full checks the verifier runs on an integration branch before the spec's PR: every build,
test and oracle, the benchmarks and the ratchet regeneration.
_Avoid_: full build (only one part of it)

**Milestone gate**:
The check, by the gate-reviewer on one commit of `main`, that every condition of `docs/GATE.md`
has observed evidence.

**Finding**:
A written contradiction between practice and an ADR or the architecture, with the smallest
change that keeps the documented intent.

**Evidence word**:
One of `VERIFIED_PASS`, `EXECUTED_FAIL`, `NOT_RUN`, `ENVIRONMENT_BLOCKED`; every reported check
carries one.
