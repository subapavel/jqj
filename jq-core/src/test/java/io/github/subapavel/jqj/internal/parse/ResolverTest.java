package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.shape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.Bindings;
import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.internal.op.StepRun;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.value.JqNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parser builds an AST for every construct of jq 1.8.2's grammar, and the resolver lowers only what is built,
 * every other kind a Stub (ADR-0032, GLOSSARY "AST", "Resolver"). Below the API because the API cannot tell a parse
 * that accepts a construct from one that stubs it: both end in the Stub until the construct's ticket lowers it. As a
 * ticket lowers a kind, its entries leave the Stub list for that ticket's own tests (an entry ending with its Stub is
 * not a weakened test).
 */
class ResolverTest {

  /**
   * The precedence/* filters (diff/precedence.rec, recorded on jq-ref 1.8.2: each runs, rc 0 or 5). Each needs a kind
   * #62 does not lower, so each parses and resolves to the Stub; the recording is the owning ticket's red. A filter
   * whose kinds a later ticket lowers (#64 constructions, #65 arithmetic and negation, #66 comparison, {@code and},
   * {@code or}, {@code not} and {@code //}, #67 {@code as}, patterns, {@code ?//}, variables, {@code $__loc__} and
   * format strings, #68 {@code if}, {@code try}, {@code ?}, {@code error}, {@code label} and {@code break}, #69
   * {@code reduce} and {@code foreach}, #70 {@code def}) ends with {@code | ..}, which still stubs, here and in
   * {@link #GRAMMAR}.
   */
  private static final List<String> PRECEDENCE =
      List.of(
          "def f: 1; 2 | f | ..",
          "1 | def f: 2; f | ..",
          "1 as $x | 2 | $x | ..",
          "\"x\" as $x | \"y\" as $y | $x + $y | ..",
          "label $out | 1, break $out, 2 | ..",
          "1, 2 | . + 10 | ..",
          "1, null // 2 | ..",
          ".a = null // 2",
          ".a = true or false",
          "true or true and false | ..",
          "false == false and false | ..",
          "1 + 1 == 2 | ..",
          "1 + 2 * 3 | ..",
          "2 * 3 + 1 | ..",
          "- \"a\" * 2 | ..",
          "- 1 + 2 | ..",
          "- 1 | . + 1 | ..",
          "[- \"a\"?] | ..",
          "1 - 2 - 3 | ..",
          "1 - 2 + 3 | ..",
          "8 / 4 / 2 | ..",
          "7 % 4 * 2 | ..",
          "2 * 3 as $x | $x + 1 | ..",
          "1 // 2 as $x | $x + 10 | ..",
          "[1, 2 as $x | $x, 3] | ..",
          "reduce 1 + 2 as $x (0; . + $x) | ..",
          "foreach 1 + 2 as $x (0; . + $x) | ..",
          "try 1 catch 2 + 3 | ..",
          "try 1 + error(\"x\") | ..",
          "{a: 1 | . + 1} | ..",
          "[5,6] [1] | ..",
          "if true then 1 else 2 end + 1 | ..",
          "reduce (1,2) as $x (0; . + $x) * 2 | ..",
          "error(\"x\")? // 1 | ..");

  /**
   * One filter per other construct of the grammar. jq-ref 1.8.2, observed 2026-11-12 (.logs/62-jqref-accept.log of
   * the test-author's run): none is a syntax error; each gives rc 0 or 5, or rc 3 with a resolution error (module not
   * found for import and include). The {@code $$$$v} form is builtin.jq's {@code _modify}. #70 binds every call: the
   * entries that bound nowhere are jq's compile error ({@link #referencesThatBindNowhereAreJqsCompileError}). #70 wrapped
   * a call of a jq builtin in a user definition whose body is {@code ..}; #71's native table and prelude unwrapped them
   * (ADR-0042): an unbuilt native still stubs, and limit/2, which compiles, moved to {@link
   * #preludeCallsRunAsJqRefRunsThem}.
   */
  private static final List<String> GRAMMAR =
      List.of(
          "..",
          "..?",
          "$__loc__ | ..",
          ".a and .b | ..",
          ".a // .b | ..",
          ".a |= .b",
          ".a |= 1 | .b += 2 | .d *= 2 | .e /= 1 | .g //= 3 | .h = 4",
          ".f %= 1 | .c -= 1",
          // Unwrapped at #71: getpath/1 is an unbuilt native of spec 3 (ADR-0042) and |= is _modify's; jq-ref 1.8.2,
          // observed 2026-11-13 with -nc: {"a":1}, rc 0.
          "getpath([\"a\"]) |= 1",
          // Unwrapped at #71: tostring/0 is an unbuilt native of #72 (ADR-0042); jq-ref 1.8.2, observed 2026-11-13
          // with -nc: {"if":1,"and":2,"a":3,"b":4,"k":5,"x1":6,"1":7,"__loc__":{"file":"<top-level>","line":1},"c":null}.
          "{if: 1, and: 2, a: 3, \"b\": 4, @base64 \"k\": 5, \"x\\(1)\": 6, (1|tostring): 7, $__loc__, c}",
          "1 as $x | {$x: 2} | ..",
          "{a: -1, b: 1 | . + 1} | ..",
          "[] | ..",
          "{} | ..",
          "[1, 2] | ..",
          "-1 | ..",
          "-(1 + 2) | ..",
          "not | ..",
          "def f(g; $x): g + $x; f(1; 2) | ..",
          "def f: def g: 3; g; f | ..",
          "def f: .; def g: f; g | ..",
          "def f: 1; . | ..",
          "[foreach (1,2,3) as $x (0; . + $x; [$x, .])] | ..",
          "reduce empty as $x (0; .) | ..",
          "if false then 1 elif true then 2 else 3 end | ..",
          "if false then 1 end | ..",
          "try error(\"x\") catch . | ..",
          "try error(\"x\") | ..",
          "label $f | break $f | ..",
          ".[] as [$a, $b] | $a + $b | ..",
          ". as {a: {b: $c}} | $c | ..",
          ". as {$a} | $a | ..",
          ". as [$a, {b: $c}] | $c | ..",
          ".[] as [$a] ?// $a | $a | ..",
          ". as [$a] ?// {$a} ?// $a | $a | ..",
          "reduce .[] as [$a, $b] (0; . + $a * $b) | ..",
          "1 as $v | $$$$v",
          "module {a:1}; .",
          "import \"nosuch\" as n; .",
          "include \"nosuch\"; .",
          ".. .a",
          "@text \"a\" | ..",
          "@json",
          "1 # c\n+ 2 | ..");

  /**
   * The GRAMMAR entries that bound nowhere, moved here when #70 bound every reference (ADR-0040): a constant called
   * with arguments, another name and a variable no {@code as} binds are jq's compile error, thrown before any lowering,
   * so the {@code ..} after one never stubs. jq-ref 1.8.2, observed 2026-11-13 with -n: rc 3 and exactly these blocks.
   */
  @Test
  void referencesThatBindNowhereAreJqsCompileError() {
    Map<String, String> blocks =
        Map.of(
            "true(1)", "jq: error: true/1 is not defined at <top-level>, line 1, column 1:\n    true(1)\n    ^^^^",
            "foo", "jq: error: foo/0 is not defined at <top-level>, line 1, column 1:\n    foo\n    ^^^",
            "$x | ..", "jq: error: $x is not defined at <top-level>, line 1, column 1:\n    $x | ..\n    ^^");
    for (Map.Entry<String, String> e : blocks.entrySet()) {
      assertThatCode(() -> Parser.parse(e.getKey())).as("parse of %s", e.getKey()).doesNotThrowAnyException();
      JqCompileException error =
          assertThatExceptionOfType(JqCompileException.class).as("resolve of %s", e.getKey()).isThrownBy(() -> ir(e.getKey())).actual();
      assertThat(error.blocks()).as("blocks of %s", e.getKey()).containsExactly(e.getValue());
    }
  }

  @Test
  void rangeWithOtherThanTwoBoundsIsAStub() {
    // #69 lowers range/2 only; range/1 and range/3 are builtin.jq's, from #71's prelude (ADR-0041). jq-ref 1.8.2,
    // observed 2026-11-13 with -nc: range(5) gives 0 to 4 and range(0; 10; 3) gives 0, 3, 6, 9 (rc 0). Unwrapped at
    // #71: range/1 is range(0;$x) and runs (preludeCallsRunAsJqRefRunsThem); range/3 calls while/2, a recursive group,
    // so it stays a Stub until spec 2b (PreludeSignatures.STUBS).
    parsesToAStub("range(0; 10; 3)");
    // Witness: two bounds lower.
    assertThat(shape(ir("range(0; 5)"))).as("IR of range(0; 5)").isEqualTo("range(0; 5)[0,11)");
  }

  /**
   * The entries #70 wrapped in a user definition whose body is {@code ..} until #71's prelude and native table, unwrapped
   * (ADR-0042): limit/2 and range/1 are builtin.jq's and compile, so they left the Stub lists. jq-ref 1.8.2, observed
   * 2026-11-13 with -nc: {@code [limit(2; 1, 2, 3)]} gives [1,2] and {@code range(5)} gives 0 to 4, rc 0.
   */
  @Test
  void preludeCallsRunAsJqRefRunsThem() {
    assertThat(results("[limit(2; 1, 2, 3)]")).as("results of [limit(2; 1, 2, 3)]").containsExactly("[1,2]");
    assertThat(results("range(5)")).as("results of range(5)").containsExactly("0", "1", "2", "3", "4");
  }

  /** The results of {@code filter} on null as compact JSON, through the exported API; asserts that the run completes. */
  private static List<String> results(String filter) {
    JqEngine engine = JqEngine.builder().build();
    List<String> out = new ArrayList<>();
    boolean ranOut =
        engine
            .compile(filter)
            .run(
                JqNull.NULL,
                Bindings.builder().build(),
                v -> {
                  out.add(v.toJson());
                  return true;
                },
                engine.newWorkspace());
    assertThat(ranOut).as("run's answer for %s when the sink never stops", filter).isTrue();
    return out;
  }

  @Test
  void everyUnloweredKindParsesAndResolvesToAStub() {
    for (String text : PRECEDENCE) {
      parsesToAStub(text);
    }
    for (String text : GRAMMAR) {
      parsesToAStub(text);
    }
  }

  @Test
  void anInterpolationParsesAndItsLoweringIsAStub() {
    // jq-ref 1.8.2, observed 2026-11-12 on null: "a1b", "1", "xbnVsbA==", {"a1":2}, null, "3" and "xnull" (rc 0).
    // The string parts are parsed in #62 (jq's QQSTRING split); lowering an interpolation is #67's (F-35). #67 lowers
    // one with no format, so those entries end with | .., which still stubs; one with another format than @text stays
    // a Stub.
    for (String text : List.of("\"a\\(1)b\" | ..", "\"\\(\"\\(1)\")\" | ..", "@base64 \"x\\(.)\"", "{\"a\\(1)\": 2} | ..", ".\"a\\(1)\" | ..", "\"\\(1 + 2)\" | ..", "@json \"x\\(.)\"")) {
      parsesToAStub(text);
    }
    // Witness: an escaped backslash before ( is no interpolation (jq-ref -c '"a\\(1)b"' on null -> "a\\(1)b"), so the
    // string is one Const.
    assertThat(shape(ir("\"a\\\\(1)b\""))).as("IR of \"a\\\\(1)b\"").isEqualTo("\"a\\\\(1)b\"[0,9)");
  }

  @Test
  void builtKindsLowerToTheirIr() {
    // The kinds #33, #34 and #61 built (jq-ref 1.8.2, recorded in diff/dot.rec, field.rec and literal.rec), in UTF-8
    // byte Sources (ADR-0031): the resolver gives the same IR the parser gave before #62.
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry(".", ".[0,1)"),
            Map.entry(".a", ".a[0,2)"),
            Map.entry("1", "1[0,1)"),
            Map.entry("1.10", "1.10[0,4)"),
            Map.entry("\"s\"", "\"s\"[0,3)"),
            Map.entry("true", "true[0,4)"),
            Map.entry("false", "false[0,5)"),
            Map.entry("null", "null[0,4)"),
            Map.entry("@text", "@text[0,5)"),
            Map.entry("(.a)", ".a[1,3)"),
            Map.entry(".a | .b", "(.a[0,2) | .b[5,7))"),
            Map.entry(".a.b", "(.a[0,2) | .b[2,4))"),
            Map.entry("1 | .a", "(1[0,1) | .a[4,6))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
    assertThat(ir("."))
        .as("IR of . (an Identity, not a Stub)")
        .isEqualTo(new Identity(new Source(0, 1)));
  }

  /**
   * The entries #63 lowers left the Stub lists above (their Stub ended, ADR-0032; no input was dropped) and are
   * asserted here with the key-outer and bound orders of ADR-0034, each recorded on jq-ref 1.8.2 (diff/index.rec,
   * iterate.rec, slice.rec, comma.rec; precedence/postfix-chain; ParserTest's and ParserEdgesTest's entries). An
   * operation is named by its jq step with {@code ?} for the quiet variant: {@code .a}, {@code ."a"}, {@code .[k]}
   * (index), {@code .[a:b]} (slice, a missing bound a Const null) and {@code .[]} (iterate). A constant key or bound is
   * a Const argument; a computed one is a Bind around the target (the key outer; a outside b outside t). A navigation
   * {@code ?} is the quiet variant, never a Try.
   */
  @Test
  void navigationAndCommaLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry(".a?", ".a?[0,3)"),
            Map.entry(".\"a\"?", ".\"a\"?[0,5)"),
            Map.entry(".a?.b", "(.a?[0,3) | .b[3,5))"),
            Map.entry(".a.\"b\"?", "(.a[0,2) | .\"b\"?[2,7))"),
            Map.entry(".[0]", ".[k](0)[0,4)"),
            Map.entry(".[\"a\"]", ".[k](\"a\")[0,6)"),
            Map.entry(".a[0]", "(.a[0,2) | .[k](0)[2,5))"),
            Map.entry(".a.[0]", "(.a[0,2) | .[k](0)[2,6))"),
            Map.entry(".a[0].b", "((.a[0,2) | .[k](0)[2,5)) | .b[5,7))"),
            Map.entry(".[]", ".[][0,3)"),
            Map.entry(".[]?.a", "(.[]?[0,4) | .a[4,6))"),
            Map.entry(".[1:2], .[:1], .[1:]", "((.[a:b](1; 2)[0,6), .[a:b](null; 1)[8,13)), .[a:b](1; null)[15,20))"),
            Map.entry(".a[1:]?", "(.a[0,2) | .[a:b]?(1; null)[2,7))"),
            Map.entry(".a, .b", "(.a[0,2), .b[4,6))"),
            Map.entry(".[.a]", "(.a[2,4) as $0 | .[k]($0)[0,5))"),
            Map.entry(".a[.b]", "(.b[3,5) as $0 | (.a[0,2) | .[k]($0)[2,6)))"),
            Map.entry(".[.b]?", "(.b[2,4) as $0 | .[k]?($0)[0,6))"),
            Map.entry(".[.a:.b]", "(.a[2,4) as $0 | (.b[5,7) as $1 | .[a:b]($0; $1)[0,8)))"),
            Map.entry(".a[.s:.e]", "(.s[3,5) as $0 | (.e[6,8) as $1 | (.a[0,2) | .[a:b]($0; $1)[2,9))))"),
            Map.entry(".[(0,1):(2,3)]", "((0[3,4), 1[5,6)) as $0 | ((2[9,10), 3[11,12)) as $1 | .[a:b]($0; $1)[0,14)))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #67's lowerings (ADR-0038), each run on jq-ref 1.8.2 in diff/bind.rec and interpolation.rec: {@code E as $x} is
   * one Bind; a pattern's steps are non-quiet indexes of its level's slot, array elements last first; a Var or Const
   * key, index or operand is an argument without a Bind; an interpolation's Identity and text parts are arguments; a
   * {@code ?//} is an AltBind whose alternatives end in an Identity leaf, a name an alternative never stores reading
   * one Const null bound around the chain.
   */
  @Test
  void variablesPatternsAndInterpolationsLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("1 as $x | $x", "(1[0,1) as $0 | $0)"),
            Map.entry("1 as $i | .[$i]", "(1[0,1) as $0 | .[k]($0)[10,15))"),
            Map.entry("1 as $x | $x + .", "(1[0,1) as $0 | _plus($0; .)[10,16))"),
            Map.entry(". as [$a, $b] | $a", "(.[0,1) as $0 | (($0 | .[k](1)[10,12)) as $1 | (($0 | .[k](0)[6,8)) as $2 | $2)))"),
            Map.entry("\"b\" as $k | . as {($k): $v} | $v", "(\"b\"[0,3) as $0 | (.[12,13) as $1 | (($1 | .[k]($0)[18,26)) as $2 | $2)))"),
            Map.entry(". as {$a: [$b]} | $b", "(.[0,1) as $0 | (($0 | .[k](\"a\")[6,14)) as $1 | (($1 | .[k](0)[11,13)) as $2 | $2)))"),
            Map.entry("\"a\\(.)b\"", "interpolation(\"a\"; .; \"b\")[0,8)"),
            Map.entry("\"\\(1, 2)\\(.a)\"", "(.a[10,12) as $0 | ((1[3,4), 2[6,7)) as $1 | interpolation($1; $0)[0,14)))"),
            Map.entry(". as [$a] ?// $a | $a", "(.[0,1) as $0 | (($0 | .[k](0)[6,8)) as $1 | .[5,9)) -> [$1] ?// .[14,16) -> [$0] as [$2] | $2)"),
            Map.entry(
                ". as [$a] ?// $b | $a",
                "(null[0,21) as $0 | (.[0,1) as $1 | (($1 | .[k](0)[6,8)) as $2 | .[5,9)) -> [$2, $0] ?// .[14,16) -> [$0, $1] as [$3, $4] | $3))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #68's lowerings (ADR-0039), the positive witnesses of the entries that left the Stub lists above: an elif is the If
   * of its else and a missing else an Identity; {@code try} without {@code catch} and {@code f?} take {@code empty} as
   * their handler, a {@code ?} spanning its target; {@code error(f)} is {@code f | error} at the call's Source; a Break
   * names the innermost Label of its name, and a label's name is apart from a variable's. jq-ref 1.8.2, observed
   * 2026-11-13 with -nc: {@code [1 as $f | label $f | $f, break $f, 2]}, {@code [label $f | label $f | 1, break $f, 2]}
   * and {@code [label $a | label $b | 1, break $a, 2]} each give [1], {@code [if false then 1 end]} [null],
   * {@code [(1, 2)?]} [1,2] and {@code [try error("x") catch .]} ["x"].
   */
  @Test
  void controlFlowLowersToItsIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("if true then 1 else 2 end", "(if true[3,7) then 1[13,14) else 2[20,21))"),
            Map.entry("if false then 1 end", "(if false[3,8) then 1[14,15) else .[0,19))"),
            Map.entry(
                "if false then 1 elif true then 2 else 3 end",
                "(if false[3,8) then 1[14,15) else (if true[21,25) then 2[31,32) else 3[38,39)))"),
            Map.entry("try 1 catch 2", "(try 1[4,5) catch 2[12,13))"),
            Map.entry("try 1", "(try 1[4,5) catch empty[0,5))"),
            Map.entry("1?", "(try 1[0,1) catch empty[0,2))"),
            Map.entry("(1, 2)?", "(try (1[1,2), 2[4,5)) catch empty[0,7))"),
            Map.entry("error", "error[0,5)"),
            Map.entry("try error(\"x\") catch .", "(try (\"x\"[10,13) | error[4,14)) catch .[21,22))"),
            Map.entry("label $f | break $f", "(label $0 | break $0[11,19))"),
            Map.entry("label $f | label $f | break $f", "(label $0 | (label $1 | break $1[22,30)))"),
            Map.entry("label $a | label $b | break $a", "(label $0 | (label $1 | break $0[22,30)))"),
            Map.entry("1 as $f | label $f | $f, break $f", "(1[0,1) as $0 | (label $1 | ($0, break $1[25,33))))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #69's lowerings (ADR-0041), the positive witnesses of the entries that left the Stub lists above: a {@code reduce}
   * is a Reduce whose state slot its init sets, around the Bind or AltBind of its source and pattern, whose body is the
   * Advance of the slot on the update, and its result goes through {@code _state}, as the update's input does, which
   * makes an empty state jq's null; a {@code foreach} the same Foreach, with no {@code _state} after it, the Advance
   * piped into the extract when there is one; {@code range(a; b)} an Intrinsic of range on its bounds, {@code a} bound
   * outside {@code b}, an Identity, Const or Var bound an argument. jq-ref 1.8.2, observed 2026-11-13 with -nc: the precedence entries give 3, 3, 6
   * (precedence/reduce-source-takes-a-sum, foreach-source-takes-a-sum, reduce-is-a-term); {@code [foreach (1,2,3) as
   * $x (0; . + $x; [$x, .])]} gives [[1,1],[2,3],[3,6]], {@code reduce empty as $x (0; .)} 0, {@code reduce .[] as
   * [$a, $b] (0; . + $a * $b)} on [[1,2],[3,4]] 14, {@code [foreach . as $x (0; 1; 2)]} [2], {@code reduce . as [$a]
   * ?// $a (0; $a)} on [5] 5, {@code [range(0; 5)]} [0,1,2,3,4], {@code [range(.a; .b)]} on {"a":1,"b":3} [1,2] and
   * {@code [1 as $n | range(.; $n)]} on 0 [0].
   */
  @Test
  void loopsAndRangeLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("reduce 1 + 2 as $x (0; . + $x)", "((reduce $0 = 0[20,21) | (3[7,12) as $1 | (advance $0 | (_state[23,29) | _plus(.; $1)[23,29))))) | _state[0,30))"),
            Map.entry("foreach 1 + 2 as $x (0; . + $x)", "(foreach $0 = 0[21,22) | (3[8,13) as $1 | (advance $0 | (_state[24,30) | _plus(.; $1)[24,30)))))"),
            Map.entry(
                "reduce (1,2) as $x (0; . + $x) * 2",
                "(((reduce $1 = 0[20,21) | ((1[8,9), 2[10,11)) as $2 | (advance $1 | (_state[23,29) | _plus(.; $2)[23,29))))) | _state[0,30)) as $0 | _multiply($0; 2)[0,34))"),
            Map.entry(
                "[foreach (1,2,3) as $x (0; . + $x; [$x, .])]",
                "[(foreach $0 = 0[24,25) | (((1[10,11), 2[12,13)), 3[14,15)) as $1 | ((advance $0 | (_state[27,33) | _plus(.; $1)[27,33))) | [($1, .[40,41))][35,42))))][0,44)"),
            Map.entry("reduce empty as $x (0; .)", "((reduce $0 = 0[20,21) | (empty[7,12) as $1 | (advance $0 | (_state[23,24) | .[23,24))))) | _state[0,25))"),
            Map.entry(
                "reduce .[] as [$a, $b] (0; . + $a * $b)",
                "((reduce $0 = 0[24,25) | (.[][7,10) as $1 | (($1 | .[k](1)[19,21)) as $2 | (($1 | .[k](0)[15,17)) as $3 | (advance $0 | (_state[27,38) | (_multiply($3; $2)[31,38) as $4 | _plus(.; $4)[27,38)))))))) | _state[0,39))"),
            Map.entry("foreach . as $x (0; 1; 2)", "(foreach $0 = 0[17,18) | (.[8,9) as $1 | ((advance $0 | (_state[20,21) | 1[20,21))) | 2[23,24))))"),
            Map.entry(
                "reduce . as [$a] ?// $a (0; $a)",
                "((reduce $0 = 0[25,26) | (.[7,8) as $1 | (($1 | .[k](0)[13,15)) as $2 | .[12,16)) -> [$2] ?// .[21,23) -> [$1] as [$3] | (advance $0 | (_state[28,30) | $3)))) | _state[0,31))"),
            Map.entry("range(0; 5)", "range(0; 5)[0,11)"),
            Map.entry("range(.a; .b)", "(.a[6,8) as $0 | (.b[10,12) as $1 | range($0; $1)[0,13)))"),
            Map.entry("1 as $n | range(.; $n)", "(1[0,1) as $0 | range(.; $0)[10,22))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #70's lowerings (ADR-0040), the positive witnesses of the entries that left the Stub lists above: a definition
   * makes no node, and a call of it is its body, inlined, at the body's Sources; a filter parameter's call is its
   * argument lowered where the call is written, a parameter passed on as it is; a value parameter's argument is
   * lowered once and bound around the body, its name also callable; a definition sees the definitions before it; a
   * user definition hides the native of its name; a label is seen through a definition; a definition the main filter
   * never calls is not lowered. jq-ref 1.8.2, observed 2026-11-13 with -c, in order: 1 on null, 5 on {"a":{"a":5}}, 2
   * and 2 on null, 1 on {"a":1,"b":2}, 3 on {"a":3}, then on null 5, 7, [] for the label one inside [...], 3, 2, 1 and
   * null. The shadowing of {@code not} and {@code range/2} is also recorded (diff/defs.rec).
   */
  @Test
  void definitionsLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("def f: 1; f", "1[7,8)"),
            Map.entry("def f(g): g | g; f(.a)", "(.a[19,21) | .a[19,21))"),
            Map.entry("def f($x): $x + $x; f(1)", "(1[22,23) as $0 | _plus($0; $0)[11,18))"),
            Map.entry("def f(g): 1 as $x | g; 2 as $x | f($x)", "(2[23,24) as $0 | (1[10,11) as $1 | $0))"),
            Map.entry("def f: .a; def g: f; def f: .b; g", ".a[7,9)"),
            Map.entry("def f(g): g; def h(g): f(g); h(.a)", ".a[31,33)"),
            Map.entry("def not: 5; not", "5[9,10)"),
            Map.entry("def range(a; b): 7; range(1; 2)", "7[17,18)"),
            Map.entry("label $l | def f: break $l; f", "(label $0 | break $0[18,26))"),
            Map.entry("def f: def g: 3; g; f", "3[14,15)"),
            Map.entry("def f($a): a + $a; f(1)", "(1[21,22) as $0 | _plus(1; $0)[11,17))"),
            Map.entry("def f: ..; 1", "1[11,12)"),
            Map.entry(".?", "(try .[0,1) catch empty[0,2))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * F-42, F-44: a run of {@link Resolver#STEP_RUN} steps of one result or none stays a Pipe of its steps; one more is
   * one StepRun, named by and spanning the steps' text. Field reads, constant indexes and constant slices make such a
   * run; an iterate ends it. LongPathChainTest runs the fused runs.
   */
  @Test
  void aRunOfStepsOfOneResultPastTheParseDepthIsOneStep() {
    assertThat(Resolver.STEP_RUN).as("the longest run kept, the parser's nesting bound").isEqualTo(512);
    String kept = ".a".repeat(Resolver.STEP_RUN);
    assertThat(ir(kept)).as("IR of %d reads", Resolver.STEP_RUN).isInstanceOf(Pipe.class);
    String fused = ".a".repeat(Resolver.STEP_RUN + 1);
    Node run = ir(fused);
    assertThat(run).as("IR of %d reads", Resolver.STEP_RUN + 1).isInstanceOf(Intrinsic.class);
    assertThat(shape(run)).as("shape of %d reads", Resolver.STEP_RUN + 1).isEqualTo(fused + "[0," + fused.length() + ")");
    assertThat(((Intrinsic) run).op().form()).as("step of %d reads", Resolver.STEP_RUN + 1).isInstanceOf(StepRun.class);
    // Constant indexes and slices on the leading dot, which the run's span takes.
    String mixed = "." + "[0][1:2][\"k\"]".repeat(171);
    assertThat(shape(ir(mixed))).as("shape of 513 constant indexes and slices").isEqualTo(mixed + "[0," + mixed.length() + ")");
    // An iterate has many results, so it ends the run.
    assertThat(shape(ir(fused + "[]"))).as("shape of %d reads and an iterate", Resolver.STEP_RUN + 1).isEqualTo("(" + fused + "[0," + fused.length() + ") | .[][" + fused.length() + "," + (fused.length() + 2) + "))");
  }

  /**
   * F-44: a chain of {@link Resolver#PIPE_CHAIN} nodes stays a Pipe chain, as deep as it is long; one more is piped
   * balanced, about log2 of its length deep. Each iterate is a node of its own, and so is an index by a variable.
   */
  @Test
  void aChainOfMoreNodesThanThePipeChainIsPipedBalanced() {
    assertThat(Resolver.PIPE_CHAIN).as("the most nodes kept a Pipe chain, an eighth of the IR depth bound").isEqualTo(64);
    assertThat(depth(ir("." + "[]".repeat(Resolver.PIPE_CHAIN)))).as("depth of %d iterates", Resolver.PIPE_CHAIN).isEqualTo(Resolver.PIPE_CHAIN);
    assertThat(depth(ir("." + "[]".repeat(Resolver.PIPE_CHAIN + 1)))).as("depth of %d iterates", Resolver.PIPE_CHAIN + 1).isEqualTo(8);
    assertThat(depth(ir("." + "[]".repeat(513)))).as("depth of 513 iterates").isEqualTo(11);
    assertThat(depth(ir("1 as $k | ." + "[$k]".repeat(100_000)))).as("depth of 10⁵ indexes by a variable").isEqualTo(19);
  }

  /**
   * F-44: a {@code ?} after another covers the first's Try, so only the last Try is built, around both;
   * LongPathChainTest runs both filters, and {@code [1 | .[0]?? | .a]}, against jq-ref's results.
   */
  @Test
  void aQuietStepAfterAnotherBuildsOneTry() {
    assertThat(shape(ir(".[0]??[0]??"))).as("IR of .[0]??[0]??").isEqualTo("(try (.[k]?(0)[0,5) | .[k]?(0)[6,10)) catch empty[6,11))");
    // A computed key between them binds around the first Try, as the nested form does.
    assertThat(shape(ir(".[0]??[.a]??")))
        .as("IR of .[0]??[.a]??")
        .isEqualTo("(try (.a[7,9) as $0 | ((try .[k]?(0)[0,5) catch empty[1,6)) | .[k]?($0)[6,11))) catch empty[6,12))");
  }

  /** The depth of {@code node}: the most nodes on a path from it to a leaf, an Intrinsic's arguments not counted. */
  private static int depth(Node node) {
    return 1
        + switch (node) {
          case Pipe p -> Math.max(depth(p.left()), depth(p.right()));
          case Bind b -> Math.max(depth(b.value()), depth(b.body()));
          case Try t -> Math.max(depth(t.body()), depth(t.handler()));
          default -> 0;
        };
  }
}
