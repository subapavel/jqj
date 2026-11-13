package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Arithmetic;
import io.github.subapavel.jqj.internal.op.BuildObject;
import io.github.subapavel.jqj.internal.op.Empty;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.Formats;
import io.github.subapavel.jqj.internal.op.Index;
import io.github.subapavel.jqj.internal.op.Iterate;
import io.github.subapavel.jqj.internal.op.JqTruth;
import io.github.subapavel.jqj.internal.op.ObjectKey;
import io.github.subapavel.jqj.internal.op.Slice;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
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
 * a bare {@code @text}; {@code empty}; and (#64) {@code [e]}, a Collect, and {@code {k: v, ...}}, its pairs bound
 * key-outer around one construction; and (#65) {@code + - * / %}, right operand outer, and {@code -t}, each result of
 * {@code t} negated; and (#66) {@code == != < > <= >=}, right operand outer, {@code and}, {@code or} and {@code not},
 * each an If, and {@code //}, an Alt, both on jq's truth (ADR-0037). Every other kind is a Stub.
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

  /** The compile's fold budget, carried from the parser (ADR-0035). */
  private final ConstantKey folds;

  private Resolver(ConstantKey folds) {
    this.folds = folds;
  }

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
    return new Resolver(program.folds()).lower(program.main());
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
      case Ast.Call c when c.args().isEmpty() && c.name().equals("empty") -> new Intrinsic(c.source(), Empty.OP, List.of());
      case Ast.Format f when Formats.lookup(f.name()) != null -> new Intrinsic(f.source(), Formats.lookup(f.name()), List.of());
      case Ast.ArrayCons a -> array(a);
      case Ast.ObjectCons o -> object(o);
      case Ast.Binary b when ConstantKey.arithmetic(b.op()) != null -> binary(b);
      case Ast.Binary b when ConstantKey.comparison(b.op()) != null -> operator(b, ConstantKey.comparison(b.op()));
      case Ast.Binary b when b.op() == BinOp.AND -> and(b);
      case Ast.Binary b when b.op() == BinOp.OR -> or(b);
      case Ast.Binary b when b.op() == BinOp.ALTERNATIVE -> new Alt(b.source(), lower(b.left()), lower(b.right()), JqTruth.TRUTHY);
      case Ast.Call c when c.args().isEmpty() && c.name().equals("not") -> not(c.source());
      case Ast.Neg n -> pipe(lower(n.operand()), new Intrinsic(n.source(), Arithmetic.NEGATE, List.of()));
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

  /**
   * {@code l op r} for {@code + - * / %} (#65): the constant it folds to, when both sides are constants and the fold
   * succeeds ({@link ConstantKey#constant}); otherwise {@link #operator}.
   */
  private Node binary(Ast.Binary b) {
    if (folds.constant(b) instanceof JqValue folded) {
      return new Const(b.source(), folded);
    }
    return operator(b, ConstantKey.arithmetic(b.op()));
  }

  /**
   * {@code l op r} as the call of {@code operation} on its operands: {@code r} bound outside {@code l}, so the right
   * operand is the outer loop, as jq's {@code gen_binop} runs it, an Identity or Const operand passed as an argument
   * without a Bind (ADR-0034 amendment for #65, ADR-0037). A comparison is lowered so even when both sides are
   * constants (design approval O6 of #66).
   */
  private Node operator(Ast.Binary b, Operation operation) {
    Node right = lower(b.right());
    int rightSlot = right instanceof Identity ? NO_SLOT : slotFor(right);
    Node left = lower(b.left());
    int leftSlot = left instanceof Identity ? NO_SLOT : slotFor(left);
    Intrinsic op = new Intrinsic(b.source(), operation, List.of(argument(left, leftSlot), argument(right, rightSlot)));
    return bind(right, rightSlot, bind(left, leftSlot, op));
  }

  /** {@code l and r} (ADR-0037): {@code if l then (if r then true else false) else false}, the left side outer. */
  private Node and(Ast.Binary b) {
    Node left = lower(b.left());
    Node right = lower(b.right());
    Source at = b.source();
    return when(at, left, when(right.source(), right, bool(at, true), bool(at, false)), bool(at, false));
  }

  /** {@code l or r} (ADR-0037): {@code if l then true else (if r then true else false)}, the left side outer. */
  private Node or(Ast.Binary b) {
    Node left = lower(b.left());
    Node right = lower(b.right());
    Source at = b.source();
    return when(at, left, bool(at, true), when(right.source(), right, bool(at, true), bool(at, false)));
  }

  /** {@code not} (ADR-0037, design approval C2): {@code if . then false else true}. */
  private static Node not(Source at) {
    return when(at, new Identity(at), bool(at, false), bool(at, true));
  }

  /** {@code if cond then then else otherwise} on jq's truth, spanning {@code at}. */
  private static If when(Source at, Node cond, Node then, Node otherwise) {
    return new If(at, cond, then, otherwise, JqTruth.TRUTHY);
  }

  /** A new constant {@code true} or {@code false}: the IR is a tree, so no node is shared. */
  private static Const bool(Source at, boolean value) {
    return new Const(at, value ? JqBool.TRUE : JqBool.FALSE);
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

  /** {@code [body]}: its body's results collected into a built array (ADR-0034 amendment); {@code []} is a constant. */
  private Node array(Ast.ArrayCons a) {
    if (a.body() == null) {
      return new Const(a.source(), new BuiltArray(new Object[0], 0));
    }
    return new Collect(a.source(), lower(a.body()), BuiltArray::new);
  }

  /**
   * {@code {k: v, ...}} (ADR-0034 amendment): each key bound outside its own value, each pair outside the next, a
   * constant key and an Identity or Const value passed as arguments without a Bind. A computed key is checked once its
   * pair has its value, before the next pair runs. A site whose keys are all constant builds on its shape, the distinct
   * keys in their first place, with one argument per key, the value of its last pair; an earlier pair of a repeated key
   * still runs, for its results. Any other site passes a key and a value per pair. A shorthand {@code {a}} or
   * {@code {"a b"}} is the field of its key.
   */
  private Node object(Ast.ObjectCons o) {
    List<Ast.Pair> pairs = o.pairs();
    int n = pairs.size();
    Node[] keys = new Node[n];
    int[] keySlots = new int[n];
    Node[] values = new Node[n];
    int[] valueSlots = new int[n];
    boolean shaped = true;
    for (int i = 0; i < n; i++) {
      Ast.Pair pair = pairs.get(i);
      keys[i] = key(pair.key());
      keySlots[i] = slotFor(keys[i]);
      values[i] = pair.value() == null ? shorthand(pair.key()) : lower(pair.value());
      valueSlots[i] = values[i] instanceof Identity ? NO_SLOT : slotFor(values[i]);
      shaped &= keySlots[i] == NO_SLOT;
    }
    Node body = shaped ? shaped(o.source(), keys, values, valueSlots) : new Intrinsic(o.source(), BuildObject.PAIRS, pairArguments(keys, keySlots, values, valueSlots));
    for (int i = n - 1; i >= 0; i--) {
      if (keySlots[i] != NO_SLOT) {
        Source at = keys[i].source();
        body = pipe(new Intrinsic(at, ObjectKey.CHECK, List.of(new Var(at, keySlots[i]))), body);
      }
      body = bind(keys[i], keySlots[i], bind(values[i], valueSlots[i], body));
    }
    return body;
  }

  /**
   * A pair's key: a name is a constant string; a string or {@code (query)} is lowered, so a plain string is a Const,
   * which the parser found a string (ADR-0036).
   */
  private Node key(Ast.Key key) {
    return switch (key) {
      case Ast.NameKey k -> new Const(k.source(), BuiltString.of(k.name()));
      case Ast.StringKey k -> lower(k.string());
      case Ast.QueryKey k -> lower(k.query());
      // $name and $__loc__ need variables (S2a.17) and $__loc__ itself.
      default -> throw Stub.unbuilt("the object key at byte " + key.source().start());
    };
  }

  /** The value of a shorthand pair, whose key {@link #key} lowered: a name or a string, read as a field. */
  private Node shorthand(Ast.Key key) {
    if (key instanceof Ast.NameKey k) {
      return lower(new Ast.Field(k.source(), null, k.name(), false));
    }
    Ast.StringKey k = (Ast.StringKey) key;
    return lower(new Ast.StringField(k.source(), null, k.string(), false));
  }

  /**
   * The construction of a site whose keys are all Consts of strings: its shape, the distinct keys in their first place,
   * and per key the value of its last pair. Keys compare by their UTF-8 bytes.
   */
  private static Intrinsic shaped(Source source, Node[] keys, Node[] values, int[] valueSlots) {
    List<JqString> shape = new ArrayList<>();
    List<Node> args = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      JqString key = (JqString) ((Const) keys[i]).value();
      Node value = argument(values[i], valueSlots[i]);
      int at = 0;
      while (at < shape.size() && shape.get(at).utf8().mismatch(key.utf8()) != -1) {
        at++;
      }
      if (at == shape.size()) {
        shape.add(key);
        args.add(value);
      } else {
        args.set(at, value);
      }
    }
    return new Intrinsic(source, BuildObject.shaped(shape.toArray(JqString[]::new)), args);
  }

  /** The arguments of a site with a computed key: a key and a value per pair. */
  private static List<Node> pairArguments(Node[] keys, int[] keySlots, Node[] values, int[] valueSlots) {
    List<Node> args = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      args.add(argument(keys[i], keySlots[i]));
      args.add(argument(values[i], valueSlots[i]));
    }
    return args;
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
