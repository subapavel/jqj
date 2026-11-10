# Which libraries and JDK facilities do we rely on?

Ticket #10 (Phase 0 wayfinder). Researched 2026-11-10 on the cloud container: JDK
`25.0.5.0.3+1-LTS-2` at `/opt/temurin-25`, reference jq `jq-1.8.1` at `/usr/local/bin/jq-ref`.

Evidence words follow `AGENTS.md`. `VERIFIED_PASS` here means "observed in this session"
(a `javap` listing, a probe run, a file read). `/usr/bin/javap` on this container is **JDK 21**
(`/usr/lib/jvm/java-21-openjdk-amd64`); every JDK fact below was taken with
`/opt/temurin-25/bin/javap` or run on `/opt/temurin-25/bin/java`. `openjdk.org`, `docs.oracle.com`
and `bugs.openjdk.org` do not resolve from this session (`ENVIRONMENT_BLOCKED`). JEP and JVMS
numbers are therefore cited by number and backed by the JDK 25 javadoc in `lib/src.zip` and by
probes, not by fetching the JEP pages.

## Answer in one paragraph

joni 2.2.9 (MIT, released 2026-10-29, active) is usable: Java 8 bytecode with a JPMS module, a
`byte[]`-only API, `Syntax.PerlNG` and every option jq passes. Its `PerlNG` is the older
Onigmo-era table, so it lacks constructs jq 1.8.1 accepts (possessive `a++`, conditionals,
`\K`, `\R`, absent groups, `(?&name)`). Most can be turned on with a custom `Syntax` built from
joni's own flags. Callouts and `\y` have no joni flag at all: they become oracle cases plus
`DEVIATIONS.md` entries. Everything else the architecture needs exists in JDK 25 as final API and
was observed working: the Class-File API, hidden classes with class data loaded through `condy`,
unloading of non-`STRONG` hidden classes, `MemorySegment` views over `byte[]` with zero copy, and
`Arena` lifetimes that make a stale view throw. JMH 1.37 is the current release and dates from
2023. On JDK 24+ it prints a `sun.misc.Unsafe` warning (JEP 498); master has removed that use.
PIT 1.30.2 bundles ASM 9.11, whose `Opcodes` has `V25`. JSONTestSuite is MIT. Every candidate
passes the allowlist; JMH passes only through its named benchmark-module exception.

## Dependency table

| Artifact (version checked) | Scope in jqj | Licence (source) | Allowlist (CLAUDE.md) |
|---|---|---|---|
| `org.jruby.joni:joni` 2.2.9 | runtime (`jq-core`) | MIT (POM `<licenses>`; repo `LICENSE` "MIT License, Copyright (c) 2017 JRuby Team") | PASS |
| `org.jruby.jcodings:jcodings` 1.0.64 (joni's only compile dependency) | runtime, transitive | MIT (POM `<licenses>`) | PASS |
| `org.openjdk.jmh:jmh-core` / `jmh-generator-annprocess` 1.37 | benchmark module only | GPL-2.0 with Classpath Exception (`jmh-parent-1.37.pom`) | PASS only through the named exception "JMH in the benchmark module only"; anywhere else it fails |
| `net.sf.jopt-simple:jopt-simple` 5.0.4 (JMH dependency) | benchmark, transitive | MIT (POM) | PASS |
| `org.apache.commons:commons-math3` 3.6.1 (JMH dependency) | benchmark, transitive | Apache 2.0 (inherited: `commons-parent` 39 → `org.apache:apache` 16 POM) | PASS |
| `org.pitest:pitest-maven` 1.30.2 | Maven plugin (tool) | Apache 2.0 (POM) | PASS (a plugin is a tool, not a dependency; Apache 2.0 passes in any case) |
| `org.pitest:pitest-junit5-plugin` 1.2.3 | test-time plugin dependency of PIT | Apache 2.0 (POM) | PASS |
| `org.ow2.asm:asm` 9.11 (shaded into PIT, set by `asm.version` in `pitest-parent-1.30.2.pom`) | inside the PIT tool | not checked: it sits inside a Maven plugin, so it falls under the tool rule | n/a (tool) |
| JSONTestSuite (nst/JSONTestSuite @ `1ef36fa0`) | test data, if copied | MIT ("Copyright (c) 2016 Nicolas Seriot", repo `LICENSE`) | PASS. Copied files keep the notice and are listed in `NOTICE`, the same treatment CLAUDE.md requires for jq's files |
| JDK 25 (`java.base`: `java.lang.classfile`, `java.lang.invoke`, `java.lang.foreign`) | platform | GPL-2.0 + CPE (platform, not a dependency) | n/a |

Licences were read from the POMs downloaded from Maven Central into `~/.m2` (`VERIFIED_PASS`).
The JMH and jopt-simple licences came from the POMs in that directory too.

## 1. joni

**Release and activity.** Maven Central `maven-metadata.xml` gives `<release>2.2.9</release>`.
`joni-2.2.9.jar` is dated `Last-Modified: Fri, 30 Oct 2026` (VERIFIED_PASS). Tags on GitHub:
2.2.9 (2026-10-29), 2.2.8 (2026-10-27), 2.2.7 (2026-02-25), 2.2.6 (2025-03-28), and 2.2.2–2.2.5
in 2025 (<https://github.com/jruby/joni/tags>). Recent master commits come from headius, eregon
and kares (the JRuby team), e.g. PR #90 "expansion-limit-checks" and #86 "invalid-unicode"
(<https://github.com/jruby/joni/commits/master>). The project is maintained because JRuby uses
it.

**Java 25 compatibility.** The jar manifest says `Build-Jdk-Spec: 25`. The class files are major
version 52 (Java 8), and the jar ships `module-info.class`:
`open module org.jruby.joni { requires transitive org.jruby.jcodings; exports org.joni,
org.joni.constants, org.joni.exception }`. `jcodings-1.0.64.jar` also ships `module-info.class`.
All of this was seen with `javap` (VERIFIED_PASS). joni was not loaded or run in this session,
because downloaded jars are not executed here; running it on JDK 25 is `NOT_RUN`.

**Byte-array API** (`javap` on joni 2.2.9, VERIFIED_PASS):
- `new Regex(byte[] pattern, int p, int end, int option, org.jcodings.Encoding enc, Syntax syntax, WarnCallback)`.
  The UTF-8 encoding is `org.jcodings.specific.UTF8Encoding.INSTANCE`.
- `Regex.matcher(byte[] str, int p, int end)` and `matcher(byte[], int, int, long timeout)`. The
  timeout is in nanoseconds (`Matcher.java`: `protected long timeout; // nanoseconds`). There are
  `matcherNoRegion(...)` variants without captures.
- `Matcher.search(int start, int range, int option)`, `match(...)`, and the `searchInterruptible`
  and `matchInterruptible` variants that throw `InterruptedException`. `getBegin()`, `getEnd()`
  and `getEagerRegion()` return a `Region` with `getNumRegs()`, `getBeg(i)` and `getEnd(i)`, all
  as byte offsets.
- `Regex.numberOfCaptures()`, `numberOfNames()` and `namedBackrefIterator()` (yields `NameEntry`
  with `name`, `nameP`, `nameEnd`, `getBackRefs()`). jq's `captures[].name` needs these.
- `Syntax` has a public constructor `Syntax(String, int op, int op2, int op3, int behavior,
  int options, Syntax.MetaCharTable)` and public final fields `op`, `op2`, `op3`, `behavior`
  and `options`. A jq syntax can therefore be derived from `Syntax.PerlNG`.
- The API is `byte[]` only and has no `MemorySegment` overload (see §3 for the bridge).
- Usage shape: Context7 `/jruby/joni` shows `new Regex(pattern, 0, pattern.length, Option.NONE,
  UTF8Encoding.INSTANCE)`, then `matcher.search(0, str.length, Option.DEFAULT)` and
  `getEagerRegion()`.

**What jq passes to Oniguruma.** jq 1.8.1 `src/builtin.c` `f_match`
(<https://raw.githubusercontent.com/jqlang/jq/jq-1.8.1/src/builtin.c>, lines ~931–1000):
- `onig_new(..., options, ONIG_ENCODING_UTF8, ONIG_SYNTAX_PERL_NG, &einfo)`. The base options
  are `ONIG_OPTION_CAPTURE_GROUP`.
- Flags: `g` sets global (a jq loop, not an Oniguruma option), `i` → `IGNORECASE`, `x` →
  `EXTEND`, `m` → `MULTILINE`, `s` → `SINGLELINE`, `p` → `MULTILINE|SINGLELINE`, `l` →
  `FIND_LONGEST`, `n` → `FIND_NOT_EMPTY`. Any other letter is an error: "… is not a valid
  modifier string".
- `onig_search(reg, str, end, start, end, region, ONIG_OPTION_NONE)`. Errors come back as
  `"Regex failure: " + onig_error_code_to_str`.
- joni has every one of these: `org.joni.Option` has `IGNORECASE`, `EXTEND`, `MULTILINE`,
  `SINGLELINE`, `FIND_LONGEST`, `FIND_NOT_EMPTY`, `CAPTURE_GROUP` and `NONE`, and `org.joni.Syntax`
  has `PerlNG` (javap, VERIFIED_PASS).

**The vendored Oniguruma and the syntax gap.** jq 1.8.1 vendors Oniguruma at
`4ef89209a239c1aea328cf13c05a2807e5c146d1` (<https://github.com/jqlang/jq/tree/jq-1.8.1/vendor>).
Its `oniguruma.h` there is 6.9.10. Its `OnigSyntaxPerl_NG` (`src/regsyntax.c` at that commit)
enables these op2 flags: possessive repeat/interval, `(?(cond)…)`, `(?~…)` absent, callout
contents/names, `\X`/`\y` text segment, `\K`, `\R`, `\N`/`\O`, `(?&name)` Perl subexp call,
`\o{…}` octal, and `ISOLATED_OPTION_CONTINUE_BRANCH`. All of them sit on top of the Perl base
set. joni 2.2.9 `Syntax.PerlNG` (<https://raw.githubusercontent.com/jruby/joni/joni-2.2.9/src/org/joni/Syntax.java>, line 683)
enables only `\Q…\E`, group effects, Perl options, `\p{…}`, `(?<name>)`, `\k<name>` and
`\g<name>`, with option `SINGLELINE`. The joni bit layout (`constants/SyntaxProperties.java`)
also differs from kkos Oniguruma 6.9 (e.g. joni `OP2_QMARK_LPAREN_CONDITION = 1<<29`). A jq
syntax has to be built from joni's own constants; the C values cannot be copied over.

What jq-ref 1.8.1 accepts, run in this session (VERIFIED_PASS), with the joni flag that would
cover each:

| jq-ref probe | result | joni 2.2.9 flag |
|---|---|---|
| `"aaa"\|test("a++")` | `true` | `OP2_PLUS_POSSESSIVE_REPEAT` |
| `"ab"\|test("(?<x>a)(?(<x>)b\|c)")` | `true` | `OP2_QMARK_LPAREN_CONDITION` |
| `"foobar"\|[match("foo\\Kbar").string]` | `["bar"]` | `OP2_ESC_CAPITAL_K_KEEP` |
| `"a\nb"\|test("a\\Rb")` | `true` | `OP2_ESC_CAPITAL_R_LINEBREAK` |
| `"aXb"\|test("(?~X)")` | `true` | `OP2_QMARK_TILDE_ABSENT` |
| `"abab"\|[match("(?<n>ab)(?&n)").string]` | `["abab"]` | `OP2_QMARK_SUBEXP_CALL` |
| `"x"\|test("\\o{170}")` | `true` | `OP_ESC_O_BRACE_OCTAL` |
| `"ab"\|test("a\\yb")` | `true` | none (no text-segment flag in joni) |
| `"ab"\|test("(?{foo})a")`, `test("(*FAIL)\|a")` | `true`, `true` | none (no callouts in joni) |
| `"ab"\|test("\\pL")` | `false` | n/a (likely because the vendored table lacks `ESC_P_WITH_ONE_CHAR_PROP`, which Oniguruma master has; cause not verified) |
| `"a\nb"\|test("a.b")` / `;"m"` / `;"s"` | `false` / `true` / `false` | Oniguruma `MULTILINE` = dot matches newline |
| `"a\nb"\|test("^b")` / `;"s"` | `false` / `false` | observed only; cause not traced (PerlNG default option is `SINGLELINE`) |
| `"ab"\|[match("(?<a>a)(?<a>b)").captures[].name]` | `["a","a"]` | `ALLOW_MULTIPLEX_DEFINITION_NAME` (present in joni PerlNG) |

What each joni flag actually does was not run (`NOT_RUN`), because joni is not executed here.
The table names the flags a jqj syntax should enable. A follow-up ticket should turn every row
into an oracle case and record what is left over (callouts, `\y`, and any semantic difference) in
`DEVIATIONS.md`.

**Other joni facts.** Timeouts and interrupts (`matcher(..., timeout)`, `searchInterruptible`)
can implement the budgets of founding architecture §6.6 for regex work. 2.2.9 also leaves out
stack traces on `JOniException` unless `-Dorg.joni.enableStackTrace=true` (PR #89), which makes
the regex error path cheap.

## 2. Class-File API and hidden classes

- **Final API.** `java.lang.classfile.ClassFile` carries `@since 24` (JDK 25 `src.zip`), and
  `javap -v` shows no preview marker. This is the final API of JEP 484 (JDK 24). Entry points
  (javap, VERIFIED_PASS): `ClassFile.of(Option...)`, `build(ClassDesc, Consumer<ClassBuilder>)`,
  `verify(byte[])` returning `List<VerifyError>`, `parse(byte[])`, `transformClass(...)`,
  `latestMajorVersion()` (the probe printed `69`). `CodeBuilder` has
  `ldc(ConstantDesc)` and `invokedynamic(DynamicCallSiteDesc)`.
- **Hidden class with class data** (JEP 371, JDK 15; class data since 16). JDK 25 javap shows
  `Lookup.defineHiddenClassWithClassData(byte[], Object classData, boolean initialize,
  ClassOption...)`, `MethodHandles.classData(Lookup, String, Class)` and
  `MethodHandles.classDataAt(Lookup, String, Class, int)`. `ClassOption` is `{NESTMATE, STRONG}`.
  The javadoc `@apiNote` says to load class data "as dynamically-computed constant(s) via a
  bootstrap method", that class data is visible only to the hidden class's own lookup, and that a
  `List` should be unmodifiable (`List.of`).
- **Constant dynamic** (JEP 309, JDK 11). `ConstantDescs.BSM_CLASS_DATA` and `BSM_CLASS_DATA_AT`
  exist (javap). The probe (`Probe.java`, outside the repo) built a class with the Class-File API,
  `ldc` of `DynamicConstantDesc.ofNamed(BSM_CLASS_DATA_AT, DEFAULT_NAME, CD_String, 1)`, `verify`
  returned 0 errors, and `defineHiddenClassWithClassData(bytes, List.of("zero","one"), true)`
  returned `one` from the generated method (VERIFIED_PASS). Literals, key bytes, shapes and joni
  `Regex` objects can travel this way, as founding architecture §9 intends.
- **Unloading.** Javadoc of `defineHiddenClass` (JDK 25 `src.zip`, `MethodHandles.java` ~l.1921–1950):
  by default a hidden class "may be unloaded even if" its defining loader is reachable. `STRONG`
  ties it to the loader, and then "the JVM may run out of memory". Probe: after dropping all
  references to a non-`STRONG` hidden class, a `WeakReference` to its `Class` was cleared after
  `System.gc()` (VERIFIED_PASS on G1 with defaults, `ClassUnloading = true`). Founding
  architecture §6.8 holds as long as filters are defined without `STRONG`.
- **Size limits.** A method's `code_length` must be < 65536 (JVMS §4.7.3), and constant-pool
  count, fields and methods are u2-bounded (JVMS §4.11). The JVMS was not fetched
  (`ENVIRONMENT_BLOCKED`). Probe: building a method of 70 001 bytes threw
  `IllegalArgumentException: Code length 70001 is outside the allowed range in m()void` at
  `build` time (VERIFIED_PASS), so the limit surfaces in our code generator, not at define time.
- **JIT method-size limit.** HotSpot `compiler_globals.hpp` at `jdk-25-ga`:
  `product(bool, DontCompileHugeMethods, true, ...)` and `develop(intx, HugeMethodLimit, 8000,
  ...)` (<https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/hotspot/share/compiler/compiler_globals.hpp>, l.385–390).
  `-XX:+PrintFlagsFinal` on this JDK shows `DontCompileHugeMethods = true`, `FreqInlineSize = 325`
  and `MaxInlineSize = 35` (VERIFIED_PASS). `HugeMethodLimit` is a `develop` flag, fixed at 8000
  bytecode bytes in product builds. That is the per-method budget §9 refers to: a generated
  method over 8000 bytes runs only in the interpreter.

## 3. MemorySegment reads and Arena lifetimes

The FFM API is final in JDK 22 (JEP 454); `MemorySegment` carries `@since 22` in `src.zip`.
Observed by probe on JDK 25 (VERIFIED_PASS):
- `MemorySegment.ofArray(byte[])` makes a heap segment. `get(ValueLayout.JAVA_BYTE, i)` reads it,
  and an out-of-range read throws `IndexOutOfBoundsException`.
- `heapBase()` returns the original `byte[]` (identity equal), and on a slice `address()` is the
  array offset (`asSlice(1,3).address() == 1`). This gives a **zero-copy bridge to joni** for heap
  documents: `heapBase().get()` plus `address()` as `p`. The javadoc warns that `heapBase()` is
  empty for native **and for read-only** segments. A document wrapped `asReadOnly()`, a direct
  buffer or a mapped file needs a copy (`toArray(JAVA_BYTE)`) of the string bytes before calling
  joni. Strings with escapes have to be decoded anyway.
- `asReadOnly()` followed by `set` throws `IllegalArgumentException`.
- `Arena.ofConfined()`: the segment is alive inside try-with-resources. A read after `close()`
  throws `IllegalStateException: Already closed`, and a read from another thread throws
  `WrongThreadException`. That is the "stale view throws" of founding architecture §6.2. The
  `Arena` javadoc also says a confined arena can be closed only by its owner thread. A Flottila
  step on one virtual thread fits `ofConfined`; a document shared across threads needs
  `ofShared()` (javadoc, `Arena.java` l.146–157).
- `FileChannel.map(MapMode, long, long, Arena)` returns a `MemorySegment` (javap), so a mapped
  file's lifetime is that arena.
- Other reads that exist (javap): `mismatch(...)` (static and instance), `getString(long, Charset)`,
  `ofBuffer(Buffer)`, `reinterpret(long)`.

## 4. JMH on JDK 25

- **Latest release 1.37**, tagged 2023-08-03 (Maven Central `<release>1.37</release>`;
  <https://github.com/openjdk/jmh/tags>). No release has followed it, and the archetype on master
  says `1.38-SNAPSHOT` (Context7 `/openjdk/jmh`).
- **Known issue.** 1.37's `org.openjdk.jmh.util.Utils` reads `sun.misc.Unsafe.theUnsafe`
  (<https://raw.githubusercontent.com/openjdk/jmh/1.37/jmh-core/src/main/java/org/openjdk/jmh/util/Utils.java>, l.27–47).
  Since JEP 498 (JDK 24), the first use of a memory-access method in `sun.misc.Unsafe` prints a
  warning. The jmh-dev thread "7903898: JMH: Unsafe deprecation warnings are printed in JDK 24"
  (<https://mail.openjdk.org/pipermail/jmh-dev/2024-December/004019.html>) and JDK-8351670 cover
  it; both were seen only as search results (`ENVIRONMENT_BLOCKED` for bugs.openjdk.org). The
  master `Utils.java` no longer mentions `Unsafe` (VERIFIED_PASS by grep).
  Consequence: expect a warning line on JDK 25 with 1.37. The JVM flag
  `--sun-misc-unsafe-memory-access=allow` silences it. Running JMH on JDK 25 is `NOT_RUN`.
- Setup: `jmh-core` plus `jmh-generator-annprocess` (provided scope, used as the annotation
  processor). GC profiler: `-prof gc` or `addProfiler(GCProfiler.class)` (Context7 `/openjdk/jmh`,
  `JMHSample_35_Profilers`).

## 5. PIT on JDK 25

- **Latest `pitest-maven` 1.30.2** (Maven Central, updated 2026-11-05), Apache 2.0.
  `pitest-junit5-plugin` 1.2.3 (2025-05-20) declares `junit-platform-launcher` as `provided`, so
  the project's JUnit Platform version is the one used.
- **JDK 25 support.** Release notes (<https://github.com/hcoles/pitest/releases>): 1.25.8 "Fix
  BigDecimal and BigInteger mutators for java 25", 1.25.6 "ASM 9.10.1", 1.30.1 "Bump asm to 9.11".
  `pitest-parent-1.30.2.pom` sets `<asm.version>9.11</asm.version>`, and `javap` on `asm-9.11.jar`
  shows `Opcodes.V25` (and V26–V28) (VERIFIED_PASS). Class files of major 69 can therefore be read.
  Running PIT on JDK 25 is `NOT_RUN`.
- Config is minimal: `pitest-maven` in `build/plugins`, `targetClasses` and `targetTests` are
  optional (Context7 `/websites/pitest_quickstart`).

## 6. JSON parsing conformance suite

- **JSONTestSuite** (<https://github.com/nst/JSONTestSuite>), MIT, "Copyright (c) 2016 Nicolas
  Seriot" (repo `LICENSE`, VERIFIED_PASS). In `test_parsing/`, `y_` files must be accepted, `n_`
  files must be rejected, and `i_` files may go either way (README). The newest commit is
  `1ef36fa01286573e846ac449e8683f8833c5b26a` (2024-11-22), so the suite is stable. A pin should
  name that sha. File counts were not observed: GitHub's tarball endpoint was refused here
  (`ENVIRONMENT_BLOCKED`).
- For `i_` cases jqj follows jq-ref, not the suite: what jq 1.8.1 does with an `i_` input is the
  oracle, and a difference we keep goes into `DEVIATIONS.md`. Copied files go into `NOTICE` with
  the MIT notice.

## Follow-ups this note suggests (for the architect)

1. A ticket to define the jqj regex `Syntax` (PerlNG plus the op2 flags in the table above),
   with one oracle case per probe row.
2. Callouts and `\y` cannot be done with joni 2.2.9: a `DEVIATIONS.md` candidate, or a decision.
3. Generated methods should stay under 8000 bytes of bytecode (`HugeMethodLimit`), well below the
   65 535 JVMS cap.
4. Pin JMH 1.37 and add `--sun-misc-unsafe-memory-access=allow` to the benchmark JVM args, or
   accept the warning.
