package io.github.subapavel.jqj.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.value.JqValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The parse and the resolution as two steps (ADR-0032): {@code Parser.parse} builds the AST and throws every lexical,
 * syntax and recovery error; {@code Resolver.resolve} lowers the AST to IR, a Stub for a kind it does not lower yet.
 */
final class ParseAssertions {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  static final String MARK = "jqj stub: ";

  /** jq's whole text for a program with no top-level filter (ADR-0032), which is no syntax error. */
  static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")\njq: 1 compile error";

  private ParseAssertions() {}

  /** The IR of {@code text}: its AST, resolved. */
  static Node ir(String text) {
    return Resolver.resolve(Parser.parse(text));
  }

  /** {@code text} parses, and its resolution is a Stub: a construct the parser accepts and the resolver does not lower. */
  static void parsesToAStub(String text) {
    assertThatCode(() -> Parser.parse(text)).as("parse of %s", show(text)).doesNotThrowAnyException();
    assertThatExceptionOfType(JqCompileException.class)
        .as("resolve of %s", show(text))
        .isThrownBy(() -> ir(text))
        .withMessageStartingWith(MARK);
  }

  /**
   * The parse of {@code text} throws its syntax error: {@code blocks} error blocks, the first one jq's {@code syntax
   * error, unexpected <unexpected>} part at jq's line and column, which the exception carries too.
   */
  static void syntaxError(String text, String unexpected, int line, int column, int blocks) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("parse of %s", show(text)).isThrownBy(() -> Parser.parse(text)).actual();
    assertThat(e.blocks()).as("blocks of %s", show(text)).hasSize(blocks);
    assertThat(e.blocks().getFirst())
        .as("first block of %s", show(text))
        .startsWith("jq: error: syntax error, unexpected " + unexpected)
        .contains(" at <top-level>, line " + line + ", column " + column + ":\n");
    assertThat(e.line()).as("line of %s", show(text)).isEqualTo(line);
    assertThat(e.column()).as("column of %s", show(text)).isEqualTo(column);
  }

  /** {@code text} has no top-level filter: jq's not-given text, from the parse or the resolution (ADR-0032). */
  static void notGiven(String text) {
    assertThatExceptionOfType(JqCompileException.class).as("compile of %s", show(text)).isThrownBy(() -> ir(text)).withMessage(NOT_GIVEN);
  }

  /**
   * The tree as text (ResolverTest, ParserTest): an Identity is {@code .[s,e)}, a Const its value's JSON text and {@code
   * [s,e)}, an Intrinsic its Operation's name, its arguments in parentheses when it has any, and {@code [s,e)}; a Pipe
   * {@code (left | right)}, a Comma {@code (left, right)}, a Bind {@code (value as $i | body)} and a Var {@code $i},
   * where i counts the Binds in the order they are met, so the text names which Bind a Var reads and not the slot's
   * number; an If {@code (if cond then t else e)} and an Alt {@code (left // right)} (#66). An argument is printed
   * without its Source; a Pipe, Comma, Bind, If or Alt's own Source is left out (no design fixes it). A Var with no
   * enclosing Bind of its slot is {@code $?}.
   */
  static String shape(Node node) {
    return shape(node, Map.of(), new int[1]);
  }

  /** {@code scope} maps each slot a Bind around {@code node} holds to its name; {@code met} counts the Binds met. */
  private static String shape(Node node, Map<Integer, String> scope, int[] met) {
    return switch (node) {
      case Identity i -> "." + chars(i.source());
      case Const c -> ((JqValue) c.value()).toJson() + chars(c.source());
      case Intrinsic f -> f.op().name() + arguments(f.args(), scope, met) + chars(f.source());
      case Pipe p -> "(" + shape(p.left(), scope, met) + " | " + shape(p.right(), scope, met) + ")";
      case Comma c -> "(" + shape(c.left(), scope, met) + ", " + shape(c.right(), scope, met) + ")";
      case Bind b -> {
        String name = "$" + met[0]++;
        String value = shape(b.value(), scope, met);
        Map<Integer, String> inner = new HashMap<>(scope);
        inner.put(b.slot(), name);
        yield "(" + value + " as " + name + " | " + shape(b.body(), inner, met) + ")";
      }
      case Var v -> scope.getOrDefault(v.slot(), "$?");
      case Collect c -> "[" + shape(c.body(), scope, met) + "]" + chars(c.source());
      case If i -> "(if " + shape(i.cond(), scope, met) + " then " + shape(i.then(), scope, met) + " else " + shape(i.otherwise(), scope, met) + ")";
      case Alt a -> "(" + shape(a.left(), scope, met) + " // " + shape(a.right(), scope, met) + ")";
    };
  }

  private static String arguments(List<Node> args, Map<Integer, String> scope, int[] met) {
    if (args.isEmpty()) return "";
    StringJoiner joined = new StringJoiner("; ", "(", ")");
    for (Node a : args) {
      joined.add(
          switch (a) {
            case Identity _ -> ".";
            case Const c -> ((JqValue) c.value()).toJson();
            case Var v -> scope.getOrDefault(v.slot(), "$?");
            default -> "<not atomic: " + shape(a, scope, met) + ">";
          });
    }
    return joined.toString();
  }

  static String chars(Source s) {
    return "[" + s.start() + "," + s.end() + ")";
  }

  /** The text with its control characters spelled out, for a readable description. */
  static String show(String text) {
    return "'" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
  }
}
