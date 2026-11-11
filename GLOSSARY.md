# jqj

Runs a jq filter over JSON bytes on the JVM: the filter is compiled once and then run on each
input without turning the input into an object tree.

Seeded from the founding conversation (2026-11-10) and sharpened by Phase 0 (ADR-0002 to
ADR-0023). `/domain-modeling` keeps it current; a term marked _(open)_ is waiting for a decision.

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

**Owned document**:
A document a host reads through the engine, with its own read-only tape; valid until the host
closes it, and shareable between steps and threads.
_Avoid_: shared doc

**Workspace document**:
A document read into a Workspace's reused tape; valid until that Workspace's next main read or its
close.

**Slurped document**:
The one document `-s` makes over the whole input stream, with a synthetic root array.

**Literal number**:
A number read from a document, written in a filter or made by `tonumber`. It keeps its exact
decimal value until arithmetic touches it, and prints in its canonical form.
_Avoid_: untouched number, raw number

**Computed number**:
A number made by arithmetic or a math builtin: a double, printed in jq's shortest-digit layout.
_Avoid_: double (that is the Java type)

**Canonical form**:
The text jq prints for a literal number (`1e2` → `1E+2`; `1.10` stays).
_Avoid_: normalised form

**Shape**:
The shared key list of the objects built at one construction site.
_Avoid_: hidden class, schema

**Growable object**:
A built object whose keys are made at run time, so it keeps its own key list instead of a shape.
_Avoid_: dictionary, map object, hash object

**Ownership marker**:
The `owner` field of a built array, object or string: the running activation (a `reduce` or
`foreach` run, or an update) that made the value. A write in place needs it to match and the site
to hold the in-place licence (a string append and an update's own rebuild need the match only;
ADR-0022).
_Avoid_: reference count, transient, lock

**In-place licence**:
The analysis fact that a site's target is the only use of an owning activation's state, so it may
be written in place when its owner matches (ADR-0022).

**Sole-use chain**:
The way an activation's state goes through its body, from input to result, read once and kept
nowhere else.

**Borrow**:
A read of the state whose result keeps no part of it.

**Retire**:
To give an owning activation a fresh owner when one of its values leaves the sole-use chain, so
every value it made so far is copied before any later write.

**Copy-only**:
The engine-internal switch under which no owner ever matches; the ownership tests' baseline.

**Escape**:
The moment a built value becomes reachable from somewhere other than the operation that owns it:
bound to a variable, stored in another value, emitted, captured, raised, passed to `debug`, or read
a second time by its owner.

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
A hard limit on one evaluation: recursion depth, bytes built, bytes written, steps or time. Going
over is a termination that carries a jq error message. The parse depth limit and the regex match
time are limits too, but raise jq's catchable errors (F-13, ADR-0017).
_Avoid_: quota, timeout (one budget among several)

**Host streaming**:
Running a filter of the form `<path>[] | rest` element by element, without holding the whole
document, when `rest` never looks back at it.
_Avoid_: automatic streaming (the host switches it on, F-4); `--stream` (that is jq's event mode,
a different thing)

**Walking skeleton**:
Milestone 1's first spec: the thinnest path through every layer, `.` and `.a` over one document,
under the full gates (ADR-0023).

**Host**:
The program embedding the engine. It supplies the clock, the time zone, the environment, the
module loader for `import` and the debug sink; extra inputs come per run.
_Avoid_: runtime, container, platform

**Engine**:
The immutable, thread-safe object a host builds once with its limits and host; it compiles filters
and reads documents, and keeps no filter.
_Avoid_: runtime, context

**Bindings**:
The `$name` values and `$ARGS` a host gives one run.
_Avoid_: args, params

**Extra inputs**:
The documents a run's `input` and `inputs` read, given in its run options.

**Sandbox defaults**:
What a filter may not do when embedded unless the host allows it: read the environment, name a
file, halt the JVM, import from disk, read the system time zone (ADR-0017).

**Input stream**:
The concatenated bytes of all input files or standard input, read by one parser state.
_Avoid_: stdin (one source among several)

**Detecting byte**:
The byte at which jq's parser notices an error; a message's column points at it.

**Error replay**:
Re-reading an invalid document with the translated jq parser to produce jq's message, line and
column.

**Lenient token**:
A number token jq accepts that is not strict JSON: `+1`, `.5`, `1.`, `007`, NaN and Infinity
spellings.

**Whole-container copy**:
Writing an untouched container by copying its input bytes once one walk has proved it safe.
_Avoid_: raw copy

## Verification

**Oracle**:
An independent source of the expected output for a case: jq's own test files, the reference jq
binary, the other backend or the other compile mode. A JSON conformance suite supplies inputs; the
reference jq supplies their verdicts.

**Case**:
One oracle entry with a stable id: a filter, an input, flags and the expected outputs.
_Avoid_: test (that is a JUnit method), example

**Differential oracle**:
Running a case in jqj and in the reference jq binary and requiring the same exit code, stdout and
stderr, byte for byte (syntax-error text excepted, ADR-0020).

**Backend oracle**:
Running a case in both backends and requiring identical output. It starts with the bytecode
backend (ADR-0018).

**Compile-mode oracle**:
Running a case under the optimised and the conservative compile of one backend and requiring the
same output and error.

**Conservative compile**:
Compiling with every analysis fact at its safe value: cardinality many, every path TRACKED, a
detach at every hold, no specialisation, no in-place licence.

**Recording**:
The reference jq's exit code, stdout and stderr for one case, committed beside it and written only by the
record command.
_Avoid_: snapshot, golden file

**Required case**:
A case the JUnit run asserts: it is in the ratchet, or the ticket names it at its gate.

**Survey**:
The run of every case that is not required; it writes the oracle report and fails only on a crash.

**Oracle report**:
Each case's verdict (PASS, FAIL, STALE, CRASH) per oracle and backend; the ratchet reads it.

**Deviation case**:
A differential case that carries jqj's column of a `DEVIATIONS.md` row; the reference jq's column is
its recording.

**Retired case**:
A ratcheted id whose source file changed with a reference bump.

**Reference profile**:
The reference jq's build options (decNumber, Oniguruma, builtins), which jqj mirrors.

**Ratchet**:
A committed record of what passes (oracle cases, coverage, mutation scores, benchmark
baselines). Merges may only keep or raise it. The verifier regenerates the oracle pass lists after
every merge and the rest once per spec; nobody edits it by hand.

**Floor**:
A fixed minimum of coverage or mutation score below which the build fails, whatever the ratchet
says.

**Benchmark scenario**:
One measured quantity of one filter on one input size, such as the throughput of field extract on
a 10 MB document. Its baseline is a line of the ratchet.

**Gate scenario**:
A benchmark scenario whose speed factor gate check 5 bounds (G1–G6); cold start (G7) is recorded and
ratcheted with no factor.
_Avoid_: headline benchmark

**Diagnostic scenario**:
A benchmark scenario that informs one named decision; recorded, with no factor.

**Reference time**:
The reference jq's time per document on the same machine in the same session, its start-up
subtracted.

**Speed factor**:
jqj's time per document divided by the reference time.

**Machine label**:
The name of a kind of machine (`claude-cloud-4vcpu`), recorded with its CPU, kernel and JDK.

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

**Class-C module**:
A module on a hard path, listed in `quality/floors.tsv` (ADR-0020; today `query-core` and
`jq-core`). Mutation floors apply to it, and the ticket gate
runs PIT on what a ticket changed in it.
_Avoid_: core module

**Word check**:
The check that no file of `query-core` contains the word jq or json; the project name jqj passes.

**Ticket gate**:
The automatic checks the verifier runs on one ticket's worktree before it merges: the
whole-reactor build with every oracle, PIT on the changed classes, the LF, trailer and word checks,
and the ratchet check (ADR-0014).
_Avoid_: CI (CI runs the build and script checks on every push; the gate adds PIT and the ratchet), pre-merge review

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
