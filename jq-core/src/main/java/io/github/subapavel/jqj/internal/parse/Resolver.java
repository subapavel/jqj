package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.Formats;
import io.github.subapavel.jqj.internal.op.Index;
import io.github.subapavel.jqj.internal.op.Iterate;
import io.github.subapavel.jqj.internal.op.Slice;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lowers a parsed filter to IR (GLOSSARY "Resolver", ADR-0032). Built so far (#33, #34, #61, #63): {@code .}; a field
 * by name or by a plain string, {@code .[k]}, {@code .[a:b]} and {@code .[]}, each with its quiet variant {@code ?}
 * (ADR-0034), and a chain of them and {@code |}, which nest as {@code Pipe(term, Intrinsic)}; {@code ,}; parentheses,
 * which make no node; a number or plain string literal, and {@code true}, {@code false} and {@code null}, each a Const;
 * and a bare {@code @text}. Every other kind is a Stub.
 *
 * <p>A key or bound that is a Const is the step's argument; any other is bound around the step's target, the key
 * outside the target and {@code a} outside {@code b} (ADR-0034, jq's {@code gen_index} and {@code gen_slice_index}).
 * A comma chain is lowered without recursion over its length: the parser's left-nested spine is flattened by a loop
 * and built again balanced, so its depth is about log2 of its length, with the same results and ticks (F-32).
 */
public final class Resolver {
  /** jq's message for a program with no top-level filter, which is no syntax error (ADR-0032). */
  private static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")";

  /** The IDENTs that are constants (jq's parser), when called without arguments. */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true", JqBool.TRUE, "false", JqBool.FALSE, "null", JqNull.NULL);

  /** A key or bound that is passed as a Const argument, so it takes no slot and no Bind. */
  private static final int NO_SLOT = -1;

  /** The slots taken so far: each Bind takes the next (ADR-0034). */
  private int slots;

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
    return new Resolver().lower(program.main());
  }

  private Node lower(Ast ast) {
    return switch (ast) {
      case Ast.Dot d -> new Identity(d.source());
      case Ast.Paren p -> lower(p.body());
      case Ast.Pipe p -> pipe(lower(p.left()), lower(p.right()));
      case Ast.Comma c -> comma(c);
      case Ast.Field f -> after(f.target(), field(f.source(), f.name(), f.name(), f.optional()));
      case Ast.StringField f when plain(f.name()) -> {
        BuiltString name = BuiltString.of(bytes(f.name()));
        yield after(f.target(), field(f.source(), name.toJson(), name.value(), f.optional()));
      }
      case Ast.Index i -> index(i);
      case Ast.Each e -> bracket(e.target(), e.source(), e.optional() ? Iterate.QUIET : Iterate.PLAIN, List.of());
      case Ast.Slice s -> slice(s);
      case Ast.Num n -> new Const(n.source(), LiteralNumber.ofLexeme(n.lexeme()));
      case Ast.Str s when plain(s) -> new Const(s.source(), BuiltString.of(bytes(s)));
      case Ast.Call c when c.args().isEmpty() && CONSTANTS.containsKey(c.name()) -> new Const(c.source(), CONSTANTS.get(c.name()));
      case Ast.Format f when Formats.lookup(f.name()) != null -> new Intrinsic(f.source(), Formats.lookup(f.name()), List.of());
      // Each other kind, and each guarded one outside its guard, belongs to a later ticket (spec 2a and 2b).
      default -> throw Stub.unbuilt("the " + ast.getClass().getSimpleName() + " at byte " + ast.source().start());
    };
  }

  /** The step {@code .shown} (with {@code ?} when quiet) that reads the key {@code name}. */
  private static Intrinsic field(Source source, String shown, String name, boolean quiet) {
    return new Intrinsic(source, new Operation("." + shown + (quiet ? "?" : ""), new Field(name, quiet)), List.of());
  }

  /** The step {@code step} on {@code target}'s results, or on the input when there is no target. */
  private Node after(Ast target, Intrinsic step) {
    return target == null ? step : pipe(lower(target), step);
  }

  /**
   * The bracket step {@code op} with {@code args} on {@code target}'s results, spanning {@code own}; on the leading
   * dot of {@code .[...]} it is the step alone, spanning the dot too.
   */
  private Node bracket(Ast target, Source own, Operation op, List<Node> args) {
    if (target instanceof Ast.Dot dot) {
      return new Intrinsic(new Source(dot.source().start(), own.end()), op, args);
    }
    return pipe(lower(target), new Intrinsic(own, op, args));
  }

  /** {@code t[k]}: the key outside the target. */
  private Node index(Ast.Index i) {
    Node key = lower(i.index());
    int slot = slotFor(key);
    Operation op = i.optional() ? Index.QUIET : Index.PLAIN;
    return bind(key, slot, bracket(i.target(), i.source(), op, List.of(argument(key, slot))));
  }

  /** {@code t[a:b]}: {@code a} outside {@code b} outside the target; a missing bound is a Const null. */
  private Node slice(Ast.Slice s) {
    Node from = s.from() == null ? new Const(s.source(), JqNull.NULL) : lower(s.from());
    int fromSlot = slotFor(from);
    Node to = s.to() == null ? new Const(s.source(), JqNull.NULL) : lower(s.to());
    int toSlot = slotFor(to);
    Operation op = s.optional() ? Slice.QUIET : Slice.PLAIN;
    Node step = bracket(s.target(), s.source(), op, List.of(argument(from, fromSlot), argument(to, toSlot)));
    return bind(from, fromSlot, bind(to, toSlot, step));
  }

  /** A new slot for a computed key or bound, or {@link #NO_SLOT} for a Const. */
  private int slotFor(Node value) {
    return value instanceof Const ? NO_SLOT : slots++;
  }

  /** The argument that reads {@code value}: the Const itself, or a Var of its slot. */
  private static Node argument(Node value, int slot) {
    return slot == NO_SLOT ? value : new Var(value.source(), slot);
  }

  /** {@code body} with {@code value} bound to {@code slot} around it, or {@code body} alone for a Const. */
  private static Node bind(Node value, int slot, Node body) {
    return slot == NO_SLOT ? body : new Bind(body.source(), value, slot, body);
  }

  /** A comma chain: its terms flattened by a loop, left to right, then built as a balanced tree. */
  private Node comma(Ast.Comma chain) {
    List<Ast> terms = new ArrayList<>();
    Ast left = chain;
    while (left instanceof Ast.Comma c) {
      terms.add(c.right());
      left = c.left();
    }
    terms.add(left);
    List<Node> nodes = new ArrayList<>(terms.size());
    for (int i = terms.size() - 1; i >= 0; i--) {
      nodes.add(lower(terms.get(i)));
    }
    return balanced(nodes, 0, nodes.size());
  }

  /** The terms {@code [from, to)}, at least one, as a balanced Comma tree; the left half takes the middle term. */
  private static Node balanced(List<Node> nodes, int from, int to) {
    if (to - from == 1) {
      return nodes.get(from);
    }
    int mid = from + (to - from + 1) / 2;
    Node left = balanced(nodes, from, mid);
    Node right = balanced(nodes, mid, to);
    return new Comma(new Source(left.source().start(), right.source().end()), left, right);
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
