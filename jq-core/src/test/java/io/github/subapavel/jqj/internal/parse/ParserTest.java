package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The IR the parser makes (#33 design section 1, #34 design sections 4 and 5): {@code .} is an Identity whose Source is
 * the dot's chars; {@code .name} is one FIELD token and a bare Intrinsic whose Operation is named {@code .name}; a
 * postfix chain nests left as {@code Pipe(term, Intrinsic)}; {@code |} nests right; parentheses give the inner node.
 * Every token and token sequence #34 does not build is a Stub.
 */
class ParserTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  @Test
  void theIdentitysSourceIsTheDotsChars() {
    // The whitespace before the dot is skipped, so the dot is char 2 of " \t.\n": its source is [2, 3).
    assertThat(Parser.parse(" \t.\n")).as("parse of ' \\t.\\n'").isEqualTo(new Identity(new Source(2, 3)));
    assertThat(Parser.parse(".")).as("parse of '.'").isEqualTo(new Identity(new Source(0, 1)));
  }

  /**
   * The tree as text: an Identity is {@code .[s,e)}, a Const its value's JSON text and {@code [s,e)} (#61), an
   * Intrinsic its Operation's name and {@code [s,e)}, a Pipe {@code (left | right)}. A Pipe's own Source is left out:
   * the design does not fix it.
   */
  private static String shape(Node node) {
    return switch (node) {
      case Identity i -> "." + chars(i.source());
      case Const c -> ((JqValue) c.value()).toJson() + chars(c.source());
      case Intrinsic f -> f.op().name() + chars(f.source());
      case Pipe p -> "(" + shape(p.left()) + " | " + shape(p.right()) + ")";
    };
  }

  private static String chars(Source s) {
    return "[" + s.start() + "," + s.end() + ")";
  }

  @Test
  void keywordsAfterADotAreNames() {
    // jq-ref 1.8.2, recorded in diff/field.rec: .and on {"and":1} -> 1, .if on {"if":2} -> 2, .__loc__ on
    // {"__loc__":3} -> 3, .then.else.end.or.reduce.def on the nested object -> "k", ._1.A9 -> true (rc 0 each).
    assertThat(shape(Parser.parse(".and"))).as("parse of .and").isEqualTo(".and[0,4)");
    assertThat(shape(Parser.parse(".if"))).as("parse of .if").isEqualTo(".if[0,3)");
    assertThat(shape(Parser.parse(".__loc__"))).as("parse of .__loc__").isEqualTo(".__loc__[0,8)");
    assertThat(shape(Parser.parse(".then.else.end.or.reduce.def")))
        .as("parse of .then.else.end.or.reduce.def")
        .isEqualTo("(((((.then[0,5) | .else[5,10)) | .end[10,14)) | .or[14,17)) | .reduce[17,24)) | .def[24,28))");
    assertThat(shape(Parser.parse("._1.A9"))).as("parse of ._1.A9").isEqualTo("(._1[0,3) | .A9[3,6))");

    // Each operation reads its own key: applied to one object holding every name, it gives that name's value.
    JqObject all =
        (JqObject)
            JqEngine.builder()
                .build()
                .newWorkspace()
                .read(MemorySegment.ofArray("{\"and\":1,\"if\":2,\"__loc__\":3,\"_1\":4,\"def\":5}".getBytes(UTF_8)))
                .root();
    for (Map.Entry<String, String> e : Map.of(".and", "1", ".if", "2", ".__loc__", "3", "._1", "4", ".def", "5").entrySet()) {
      Intrinsic field = (Intrinsic) Parser.parse(e.getKey());
      JqValue result = (JqValue) field.op().fn().apply(new RunState(), all);
      assertThat(result.toJson()).as("%s applied to the object", e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void parenthesesMakeNoNode() {
    // jq-ref 1.8.2, recorded: (.a) on {"a":"p"} -> "p", ((.a)) on {"a":[4]} -> [4] (rc 0).
    assertThat(Parser.parse("(.)")).as("parse of (.)").isEqualTo(new Identity(new Source(1, 2)));
    assertThat(Parser.parse("((.))")).as("parse of ((.))").isEqualTo(new Identity(new Source(2, 3)));
    assertThat(shape(Parser.parse("(.a)"))).as("parse of (.a)").isEqualTo(".a[1,3)");
    assertThat(shape(Parser.parse("((.a))"))).as("parse of ((.a))").isEqualTo(".a[2,4)");
    assertThat(shape(Parser.parse("( .a | .b )"))).as("parse of ( .a | .b )").isEqualTo("(.a[2,4) | .b[7,9))");
  }

  @Test
  void postfixNestsLeftPipeNestsRight() {
    // jq-ref 1.8.2, recorded: .a.b.c, .a|.b|.c, (.a).b, (.a | .b).c, .a .b and . .a all run (rc 0).
    Map<String, String> shapes =
        Map.of(
            ".a", ".a[0,2)",
            ".a.b.c", "((.a[0,2) | .b[2,4)) | .c[4,6))",
            ".a|.b|.c", "(.a[0,2) | (.b[3,5) | .c[6,8)))",
            "(.a).b", "(.a[1,3) | .b[4,6))",
            "(.a | .b).c", "((.a[1,3) | .b[6,8)) | .c[9,11))",
            ".a .b", "(.a[0,2) | .b[3,5))",
            ". .a", "(.[0,1) | .a[2,4))",
            ". | .a | .", "(.[0,1) | (.a[4,6) | .[9,10)))",
            ".a | (.b | .c)", "(.a[0,2) | (.b[6,8) | .c[11,13)))",
            "(.a | .b) | .c", "((.a[1,3) | .b[6,8)) | .c[12,14))");
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(Parser.parse(e.getKey()))).as("parse of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void unbuiltTokensAreStubs() {
    // Each is jq syntax #34 does not build (jq-ref 1.8.2, observed 2026-11-11 on {"a":...}: .a? .[0] .. $__loc__
    // ".a, .b" ".a |= .b" run; and, .é are syntax errors, rc 3), so each ends in the Stub. A token comes after a built
    // prefix where that matters. #61 built ."a", .1 and the comment of .a #c, so they moved to .["a"], .[] and .a[0],
    // which stay Stubs until #63 (jq-ref 1.8.2, observed 2026-11-12 on {"a":[5]}: [5], [5] and 5, rc 0 each). #c alone
    // is no program (observed the same day: "Top-level program not given", rc 3), a Stub too.
    for (String text : List.of(".a?", ".[0]", ".[\"a\"]", "..", ".[]", "$__loc__", "and", ".a and .b", ".a, .b", ",", ".a[0]", "#c", ".a |= .b", ".é", ".a.é")) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("parse of %s", text)
          .isThrownBy(() -> Parser.parse(text))
          .withMessageStartingWith(MARK);
    }
    // Witness: the built prefixes parse.
    assertThat(shape(Parser.parse(".a"))).as("witness: parse of .a").isEqualTo(".a[0,2)");
    assertThat(shape(Parser.parse(".a | .b"))).as("witness: parse of .a | .b").isEqualTo("(.a[0,2) | .b[5,7))");
  }

  @Test
  void malformedFiltersAreStubs() {
    // jq-ref 1.8.2, observed 2026-11-11: each is a syntax error, rc 3 ("unexpected end of file", "unexpected '|'",
    // "unexpected ')'", ...). #34 builds no syntax error (basics/syntax-error-eof stays red), so each is a Stub.
    for (String text : List.of(".a |", "(.a", ")", "|.a", "()", ". .", ".a .", "(.a))", ".a | | .b", "")) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("parse of '%s'", text)
          .isThrownBy(() -> Parser.parse(text))
          .withMessageStartingWith(MARK);
    }
    // Witness: the same tokens in a well-formed order parse.
    assertThat(shape(Parser.parse("(.a) | .b"))).as("witness: parse of (.a) | .b").isEqualTo("(.a[1,3) | .b[7,9))");
  }
}
