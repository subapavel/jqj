package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.shape;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Source;
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
   * {@code or}, {@code not} and {@code //}) ends with {@code | ..}, which still stubs, here and in {@link #GRAMMAR}.
   */
  private static final List<String> PRECEDENCE =
      List.of(
          "def f: 1; 2 | f",
          "1 | def f: 2; f",
          "1 as $x | 2 | $x",
          "\"x\" as $x | \"y\" as $y | $x + $y",
          "label $out | 1, break $out, 2",
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
          "[- \"a\"?]",
          "1 - 2 - 3 | ..",
          "1 - 2 + 3 | ..",
          "8 / 4 / 2 | ..",
          "7 % 4 * 2 | ..",
          "2 * 3 as $x | $x + 1",
          "1 // 2 as $x | $x + 10",
          "[1, 2 as $x | $x, 3]",
          "reduce 1 + 2 as $x (0; . + $x)",
          "foreach 1 + 2 as $x (0; . + $x)",
          "try 1 catch 2 + 3",
          "try 1 + error(\"x\")",
          "{a: 1 | . + 1} | ..",
          "[5,6] [1] | ..",
          "if true then 1 else 2 end + 1",
          "reduce (1,2) as $x (0; . + $x) * 2",
          "error(\"x\")? // 1");

  /**
   * One filter per other construct of the grammar. jq-ref 1.8.2, observed 2026-11-12 (.logs/62-jqref-accept.log of
   * the test-author's run): none is a syntax error; each gives rc 0 or 5, or rc 3 with a resolution error (module not
   * found for import and include, "true/1 is not defined", "foo/0 is not defined", "$x is not defined"). The
   * {@code $$$$v} form is builtin.jq's {@code _modify}.
   */
  private static final List<String> GRAMMAR =
      List.of(
          "..",
          "..?",
          "$__loc__",
          ".a and .b | ..",
          ".a // .b | ..",
          ".a |= .b",
          ".a |= 1 | .b += 2 | .d *= 2 | .e /= 1 | .g //= 3 | .h = 4",
          ".f %= 1 | .c -= 1",
          "getpath([\"a\"]) |= 1",
          "{if: 1, and: 2, a: 3, \"b\": 4, @base64 \"k\": 5, \"x\\(1)\": 6, (1|tostring): 7, $__loc__, c}",
          "1 as $x | {$x: 2}",
          "{a: -1, b: 1 | . + 1} | ..",
          "[] | ..",
          "{} | ..",
          "[1, 2] | ..",
          "-1 | ..",
          "-(1 + 2) | ..",
          "not | ..",
          "true(1)",
          "foo",
          "$x",
          "def f(g; $x): g + $x; f(1; 2)",
          "def f: def g: 3; g; f",
          "def f: .; def g: f; g",
          "def f: 1; .",
          "[foreach (1,2,3) as $x (0; . + $x; [$x, .])]",
          "reduce empty as $x (0; .)",
          "if false then 1 elif true then 2 else 3 end",
          "if false then 1 end",
          "try error(\"x\") catch .",
          "try error(\"x\")",
          "label $f | break $f",
          ".[] as [$a, $b] | $a + $b",
          ". as {a: {b: $c}} | $c",
          ". as {$a} | $a",
          ". as [$a, {b: $c}] | $c",
          ".[] as [$a] ?// $a | $a",
          ". as [$a] ?// {$a} ?// $a | $a",
          "reduce .[] as [$a, $b] (0; . + $a * $b)",
          "1 as $v | $$$$v",
          "module {a:1}; .",
          "import \"nosuch\" as n; .",
          "include \"nosuch\"; .",
          ".. .a",
          "@text \"a\"",
          "@json",
          "1 # c\n+ 2 | ..",
          "[limit(2; 1, 2, 3)]");

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
    // The string parts are parsed in #62 (jq's QQSTRING split); lowering an interpolation is #67's (F-35).
    for (String text : List.of("\"a\\(1)b\"", "\"\\(\"\\(1)\")\"", "@base64 \"x\\(.)\"", "{\"a\\(1)\": 2}", ".\"a\\(1)\"", "\"\\(1 + 2)\"", "@json \"x\\(.)\"")) {
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
}
