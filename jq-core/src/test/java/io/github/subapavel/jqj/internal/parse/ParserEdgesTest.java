package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.notGiven;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.show;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.syntaxError;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The implementer's witnesses of #62 for the parse paths the approved tests do not reach (ADR-0032). Every expected
 * value is jq-ref 1.8.2's, observed 2026-11-12 with {@code jq-ref -n <filter>}: a syntax block's text is jq's with its
 * "expecting" list cut (DEVIATIONS "Syntax-error text"), a recovery or lexical block is jq's exactly, and a filter that
 * parses gives rc 0, or rc 3 with no syntax error ("module not found").
 */
class ParserEdgesTest {
  private static final String TRY_FIELD = "try .[\"field\"] instead of .field for unusually named fields";
  private static final String MAY_NEED_PARENTHESES = "May need parentheses around object key expression";

  @Test
  void aMissingNameOrKeyIsASyntaxError() {
    syntaxError("import \"a\" as 1; .", "LITERAL", 1, 15, 1);
    syntaxError("def if: 1; 2", "if", 1, 5, 1);
    syntaxError("def f(): 1; f", "')'", 1, 7, 1);
    syntaxError("label 1 | 2", "LITERAL", 1, 7, 1);
    syntaxError("$$$$__loc__", "$__loc__", 1, 4, 1);
    syntaxError("{(1) 2}", "LITERAL", 1, 6, 1);
    syntaxError("\"abc", "end of file", 1, 2, 1);
    // jq adds "break requires a label to break to" from its "break" error production, which jqj does not build
    // (ADR-0032), so jqj's blocks are the first of jq's two.
    syntaxError("break 1", "LITERAL", 1, 7, 1);
  }

  @Test
  void theDirectivesAndPatternsParse() {
    for (String text :
        List.of(
            "import \"a\" as $x; .",
            "import \"a\" as x {a: 1}; .",
            ".a.\"b\"?",
            ". as $x | 1",
            ". as {\"a\": $b} | 1",
            ". as {(1|tostring): $e} | 1",
            ". as {$a: [$b]} | 1",
            "foreach . as $x (0; 1; 2)",
            "if 1 then 2 elif 3 then 4 else 5 end")) {
      parsesToAStub(text);
    }
    // A module directive or an import before definitions alone: no top-level filter either.
    notGiven("module {a: 1};");
    notGiven("import \"a\" as x; def f: 1;");
  }

  @Test
  void aLeadingDotRecoversAfterANameOrAKeywordThatNoTermTakes() {
    blocks(". a", syntax("end of file", 1, 3, ". a", "      ^"), message(TRY_FIELD, 1, 1, ". a", "    ^^^"));
    blocks(". if", syntax("if", 1, 3, ". if", "      ^^"), message(TRY_FIELD, 1, 1, ". if", "    ^^^^"));
    // After the inner dot's recovery its token comes back at once: the outer dot's recovery drops it.
    String nested = ".\"\\(. 1)\"";
    blocks(
        nested,
        syntax("LITERAL", 1, 7, nested, "          ^"),
        message(TRY_FIELD, 1, 5, nested, "        ^^^"),
        message(TRY_FIELD, 1, 1, nested, "    ^^^^^^^"));
    // The end of the text right after a recovery ends the parse, with no new block.
    blocks("(. a", syntax("end of file", 1, 4, "(. a", "       ^"), message(TRY_FIELD, 1, 2, "(. a", "     ^^^"));
    // The token the outer recovery drops is the string's quote, so the escape run after it is read, and its block
    // comes last; the parse ends at the run.
    String dropped = ".\"\\(. a \"\\x\")\"";
    blocks(
        dropped,
        syntax("QQSTRING_START", 1, 9, dropped, "            ^"),
        message(TRY_FIELD, 1, 5, dropped, "        ^^^^^"),
        message(TRY_FIELD, 1, 1, dropped, "    ^^^^^^^^^"),
        message("Invalid escape at line 1, column 4 (while parsing '\"\\x\"')", 1, 10, dropped, "             ^^"));
    // The end of the text right after the inner recovery ends the parse before the outer one.
    blocks(".\"\\(. a", syntax("end of file", 1, 7, ".\"\\(. a", "          ^"), message(TRY_FIELD, 1, 5, ".\"\\(. a", "        ^^^"));
    // A lexical block when its token is read, then the syntax error and the recovery after it.
    String lexical = "\"\\x\" + . 1";
    blocks(
        lexical,
        message("Invalid escape at line 1, column 4 (while parsing '\"\\x\"')", 1, 2, lexical, "     ^^"),
        syntax("LITERAL", 1, 10, lexical, "             ^"),
        message(TRY_FIELD, 1, 8, lexical, "           ^^^"));
  }

  @Test
  void anObjectKeyErrorStartsAtItsPairOrAtTheFirstPair() {
    // After a comma the error goes to the comma's state: its span starts at the pair, and a closing brace is dropped.
    blocks(
        "{a:1, 1+2:3}",
        syntax("LITERAL", 1, 7, "{a:1, 1+2:3}", "          ^"),
        message(MAY_NEED_PARENTHESES, 1, 7, "{a:1, 1+2:3}", "          ^^^"));
    blocks(
        "{a:1, 1}: 2}",
        syntax("LITERAL", 1, 7, "{a:1, 1}: 2}", "          ^"),
        message(MAY_NEED_PARENTHESES, 1, 7, "{a:1, 1}: 2}", "          ^^"));
    syntaxError("{a:1, b: }", "'}'", 1, 10, 1);
    // After a whole pair the error goes to the brace's state, where jq's silent '{' error '}' takes the closing brace;
    // jqj does not build it, so the parse ends there.
    syntaxError("{a 1}", "LITERAL", 1, 4, 1);
    // jq-ref gives 3 blocks here: its '{' error '}' closes the first object, then the second one recovers. jqj's one
    // block is the first of them; dropping the brace instead would run the recovery on into the second object.
    blocks("{1 2} | {3+4:5}", syntax("LITERAL", 1, 2, "{1 2} | {3+4:5}", "     ^"));
    // A recovered pair's value is the token after it; a dot's recovery inside drops its token for the object.
    blocks(
        "{a: . 1}",
        syntax("LITERAL", 1, 7, "{a: . 1}", "          ^"),
        message(TRY_FIELD, 1, 5, "{a: . 1}", "        ^^^"));
    // The colon right after the dot's recovery is the object's error at once, so it is dropped, not a key's colon.
    blocks(
        "{a: . b: 1}",
        syntax("':'", 1, 8, "{a: . b: 1}", "           ^"),
        message(TRY_FIELD, 1, 5, "{a: . b: 1}", "        ^^^^"));
    // Two tokens shifted after a recovery: the next error is not reported, and its token is dropped.
    blocks(
        "{1+2:3 4}",
        syntax("LITERAL", 1, 2, "{1+2:3 4}", "     ^"),
        message(MAY_NEED_PARENTHESES, 1, 2, "{1+2:3 4}", "     ^^^"));
    // There the token is kept, so a colon is the next recovery's.
    blocks(
        "{1+2:3 :4}",
        syntax("LITERAL", 1, 2, "{1+2:3 :4}", "     ^"),
        message(MAY_NEED_PARENTHESES, 1, 2, "{1+2:3 :4}", "     ^^^"),
        message(MAY_NEED_PARENTHESES, 1, 2, "{1+2:3 :4}", "     ^^^^^^^"));
    // The outer recovery's block comes after the blocks of its value, an object with its own recovery.
    String nested = "{a: 1 2: 3 | {1+2:3}}";
    blocks(
        nested,
        syntax("LITERAL", 1, 7, nested, "          ^"),
        syntax("LITERAL", 1, 15, nested, "                  ^"),
        message(MAY_NEED_PARENTHESES, 1, 15, nested, "                  ^^^"),
        message(MAY_NEED_PARENTHESES, 1, 2, nested, "     ^^^^^^"));
  }

  @Test
  void anUnderscoreOrASpaceEndsTheDigitsOfAUnicodeEscape() {
    // The escape run takes letters and digits after the u, so an underscore or a space after it is text.
    blocks("\"\\u_\"", message("Invalid \\uXXXX escape at line 1, column 4 (while parsing '\"\\u\"')", 1, 2, "\"\\u_\"", "     ^^"));
    blocks("\"\\u1 \"", message("Invalid \\uXXXX escape at line 1, column 5 (while parsing '\"\\u1\"')", 1, 2, "\"\\u1 \"", "     ^^^"));
  }

  @Test
  void aCommentEndsAtCrLf() {
    // The CR LF is the comment's last piece, so the end of the text is reported at the CR.
    syntaxError(".a | # c\r\n", "end of file", 1, 9, 1);
    blocks(".a | # c\r\n", syntax("end of file", 1, 9, ".a | # c\r", "            ^"));
  }

  @Test
  void theOperatorsNestByTheirLevels() {
    // jq-ref 1.8.2, observed 2026-11-12 and ADR-0032's facts: 2 * 3 as $x | $x + 1 gives 7, - 1 + 2 gives 1, try 1
    // catch 2 + 3 gives 4; the precedence/* recordings fix the levels of //, =, or and and.
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("2 * 3 as $x | $x + 1", "((2 MULTIPLY 3) as $x | ($x ADD 1))"),
            Map.entry("- 1 + 2", "(-1 ADD 2)"),
            Map.entry("try 1 catch 2 + 3", "(try 1 catch 2 ADD 3)"),
            Map.entry("try 1 + 2", "(try 1 ADD 2)"),
            Map.entry("1, 2 | 3", "((1, 2) | 3)"),
            Map.entry("1 | 2 | 3", "(1 | (2 | 3))"),
            Map.entry("1, 2, 3", "((1, 2), 3)"),
            Map.entry("1 - 2 - 3", "((1 SUBTRACT 2) SUBTRACT 3)"),
            Map.entry("1 // 2 // 3", "(1 ALTERNATIVE (2 ALTERNATIVE 3))"),
            Map.entry(".a = null // 2", "((.a ASSIGN null) ALTERNATIVE 2)"),
            Map.entry("1 or 2 and 3", "(1 OR (2 AND 3))"),
            Map.entry("1 == 2 + 3 * 4", "(1 EQUAL (2 ADD (3 MULTIPLY 4)))"),
            Map.entry("1 < 2 or 3 >= 4", "((1 LESS 2) OR (3 GREATER_EQUAL 4))"),
            Map.entry("1 / 2 % 3 * 4", "(((1 DIVIDE 2) MODULO 3) MULTIPLY 4)"),
            Map.entry(".a |= 1 // 2", "((.a UPDATE 1) ALTERNATIVE 2)"),
            Map.entry("1 | def f: 2; f, 3", "(1 | def f: 2; (f, 3))"),
            Map.entry("[1, 2 as $x | $x, 3]", "[(1, (2 as $x | ($x, 3)))]"),
            Map.entry("label $out | 1, 2", "label $out | (1, 2)"),
            Map.entry("{a: 1 | 2, b: 3}", "{a: (1 | 2), b: 3}"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(tree(Parser.parse(e.getKey()).main())).as("AST of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void aPostfixNodeSpansItsOwnBytes() {
    // ADR-0032: from the start of the suffix to its end, a ? after it included.
    Ast.Index index = (Ast.Index) Parser.parse(".a[0]?").main();
    assertThat(index.source()).as("source of [0]? in .a[0]?").isEqualTo(new Source(2, 6));
    assertThat(index.optional()).as("[0]? is optional").isTrue();
    Ast.Slice slice = (Ast.Slice) Parser.parse(".a.[1:]").main();
    assertThat(slice.source()).as("source of .[1:] in .a.[1:]").isEqualTo(new Source(2, 7));
    assertThat(slice.to()).as("the open end of .[1:]").isNull();
    Ast.Optional optional = (Ast.Optional) Parser.parse("1?").main();
    assertThat(optional.source()).as("source of ? in 1?").isEqualTo(new Source(1, 2));
    Ast.Field field = (Ast.Field) Parser.parse(". .b?").main();
    assertThat(field.source()).as("source of .b? in . .b?").isEqualTo(new Source(2, 5));
  }

  /** The parse of {@code text} throws exactly {@code expected}, in order. */
  private static void blocks(String text, String... expected) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("parse of %s", show(text)).isThrownBy(() -> Parser.parse(text)).actual();
    assertThat(e.blocks()).as("blocks of %s", show(text)).containsExactly(expected);
  }

  /** jqj's syntax block: jq's with its expecting list cut. */
  private static String syntax(String unexpected, int line, int column, String sourceLine, String carets) {
    return message("syntax error, unexpected " + unexpected, line, column, sourceLine, carets);
  }

  private static String message(String message, int line, int column, String sourceLine, String carets) {
    return "jq: error: " + message + " at <top-level>, line " + line + ", column " + column + ":\n    " + sourceLine + "\n" + carets;
  }

  /** A compact text of the AST kinds above: operators by their BinOp name, prefix forms with their bodies. */
  private static String tree(Ast ast) {
    return switch (ast) {
      case Ast.Binary x -> "(" + tree(x.left()) + " " + x.op() + " " + tree(x.right()) + ")";
      case Ast.Pipe x -> "(" + tree(x.left()) + " | " + tree(x.right()) + ")";
      case Ast.Comma x -> "(" + tree(x.left()) + ", " + tree(x.right()) + ")";
      case Ast.Neg x -> "-" + tree(x.operand());
      case Ast.Try x -> "try " + tree(x.body()) + (x.handler() == null ? "" : " catch " + tree(x.handler()));
      case Ast.Bind x -> "(" + tree(x.value()) + " as $" + ((Ast.VarPattern) x.patterns().getFirst()).name() + " | " + tree(x.body()) + ")";
      case Ast.Def x -> "def " + x.def().name() + ": " + tree(x.def().body()) + "; " + tree(x.body());
      case Ast.Label x -> "label $" + x.name() + " | " + tree(x.body());
      case Ast.ArrayCons x -> "[" + tree(x.body()) + "]";
      case Ast.ObjectCons x ->
          x.pairs().stream().map(p -> ((Ast.NameKey) p.key()).name() + ": " + tree(p.value())).collect(joining(", ", "{", "}"));
      case Ast.Field x -> (x.target() == null ? "" : tree(x.target())) + "." + x.name();
      case Ast.Num x -> x.lexeme();
      case Ast.Var x -> "$" + x.name();
      case Ast.Call x -> x.name();
      default -> ast.getClass().getSimpleName();
    };
  }
}
