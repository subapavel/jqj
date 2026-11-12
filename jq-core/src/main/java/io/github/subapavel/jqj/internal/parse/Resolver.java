package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.Formats;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Lowers a parsed filter to IR (GLOSSARY "Resolver", ADR-0032). Built so far (#33, #34, #61): {@code .}, a field by
 * name or by a plain string, a chain of them and {@code |}, which nest as {@code Pipe(term, Intrinsic)} and right;
 * parentheses, which make no node; a number or plain string literal, and {@code true}, {@code false} and {@code null},
 * each a Const; and a bare {@code @text}. Every other kind is a Stub.
 */
public final class Resolver {
  /** jq's message for a program with no top-level filter, which is no syntax error (ADR-0032). */
  private static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")";

  /** The IDENTs that are constants (jq's parser), when called without arguments. */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true", JqBool.TRUE, "false", JqBool.FALSE, "null", JqNull.NULL);

  private Resolver() {}

  /**
   * The IR of {@code program}.
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, or a Stub
   */
  public static Node resolve(Program program) {
    if (program.main() == null) {
      throw new JqCompileException(List.of(NOT_GIVEN), 1, 1);
    }
    if (program.module() != null || !program.imports().isEmpty()) {
      throw Stub.unbuilt("the module directive and imports");
    }
    return lower(program.main());
  }

  private static Node lower(Ast ast) {
    return switch (ast) {
      case Ast.Dot d -> new Identity(d.source());
      case Ast.Paren p -> lower(p.body());
      case Ast.Pipe p -> pipe(lower(p.left()), lower(p.right()));
      case Ast.Field f when !f.optional() -> index(f.target(), new Intrinsic(f.source(), new Operation("." + f.name(), new Field(f.name()))));
      case Ast.StringField f when !f.optional() && plain(f.name()) -> {
        BuiltString name = BuiltString.of(bytes(f.name()));
        yield index(f.target(), new Intrinsic(f.source(), new Operation("." + name.toJson(), new Field(name.value()))));
      }
      case Ast.Num n -> new Const(n.source(), LiteralNumber.ofLexeme(n.lexeme()));
      case Ast.Str s when plain(s) -> new Const(s.source(), BuiltString.of(bytes(s)));
      case Ast.Call c when c.args().isEmpty() && CONSTANTS.containsKey(c.name()) -> new Const(c.source(), CONSTANTS.get(c.name()));
      case Ast.Format f when Formats.lookup(f.name()) != null -> new Intrinsic(f.source(), Formats.lookup(f.name()));
      // Each other kind, and each guarded one outside its guard, belongs to a later ticket (spec 2a and 2b).
      default -> throw Stub.unbuilt("the " + ast.getClass().getSimpleName() + " at byte " + ast.source().start());
    };
  }

  /** The field {@code field} of {@code target}'s results, or of the input when there is no target. */
  private static Node index(Ast target, Intrinsic field) {
    return target == null ? field : pipe(lower(target), field);
  }

  private static Pipe pipe(Node left, Node right) {
    return new Pipe(new Source(left.source().start(), right.source().end()), left, right);
  }

  /** A string with no format and no interpolation, which is a constant. */
  private static boolean plain(Ast.Str s) {
    return s.format() == null && s.parts().stream().allMatch(Ast.Text.class::isInstance);
  }

  /** The value of a plain string: its text parts' bytes, joined. */
  private static byte[] bytes(Ast.Str s) {
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    for (Ast.Part part : s.parts()) {
      value.writeBytes(((Ast.Text) part).bytes());
    }
    return value.toByteArray();
  }
}
