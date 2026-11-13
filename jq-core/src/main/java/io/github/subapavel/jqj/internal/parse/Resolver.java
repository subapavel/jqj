package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Arithmetic;
import io.github.subapavel.jqj.internal.op.BuildObject;
import io.github.subapavel.jqj.internal.op.Empty;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.Formats;
import io.github.subapavel.jqj.internal.op.Index;
import io.github.subapavel.jqj.internal.op.Interpolate;
import io.github.subapavel.jqj.internal.op.Iterate;
import io.github.subapavel.jqj.internal.op.JqTruth;
import io.github.subapavel.jqj.internal.op.ObjectKey;
import io.github.subapavel.jqj.internal.op.Slice;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a parsed filter to IR (GLOSSARY "Resolver", ADR-0032). Built so far (#33, #34, #61, #63): {@code .}; a field
 * by name or by a plain string, {@code .[k]}, {@code .[a:b]} and {@code .[]}, each with its quiet variant {@code ?}
 * (ADR-0034), and a chain of them and {@code |}, which nest as {@code Pipe(term, Intrinsic)}; {@code ,}; parentheses,
 * which make no node; a number or plain string literal, and {@code true}, {@code false} and {@code null}, each a Const;
 * a bare {@code @text}; {@code empty}; and (#64) {@code [e]}, a Collect, and {@code {k: v, ...}}, its pairs bound
 * key-outer around one construction; and (#65) {@code + - * / %}, right operand outer, and {@code -t}, each result of
 * {@code t} negated; and (#66) {@code == != < > <= >=}, right operand outer, {@code and}, {@code or} and {@code not},
 * each an If, and {@code //}, an Alt, both on jq's truth (ADR-0037); and (#67, ADR-0038) {@code E as p | body}, its
 * pattern lowered to Binds in jq's matcher order, and {@code E as p1 ?// p2 ... | body}, an AltBind; {@code $name}, a
 * Var of its slot, a host variable's a parameter slot; {@code $__loc__}, a Const; and a string with interpolations, an
 * Intrinsic of Interpolate over its parts. Every other kind is a Stub.
 *
 * <p>A key or bound that is a Const or a Var is the step's argument; any other is bound around the step's target, the
 * key outside the target and {@code a} outside {@code b} (ADR-0034, jq's {@code gen_index} and {@code
 * gen_slice_index}). A comma chain is lowered without recursion over its length: the parser's left-nested spine is
 * flattened by a loop and built again balanced, so its depth is about log2 of its length, with the same results and
 * ticks (F-32).
 *
 * <p>Each {@code $name} that no scope holds is jq's compile error over its token, and the lowering goes on: the errors
 * are collected in the order the lowering meets them, which is jq's (jq-ref 1.8.2, recorded: diff/bind.rec
 * bind/unbound-two), so every operand left of its right one, and thrown together once the whole filter is lowered
 * (ADR-0036, ADR-0038). A Stub is thrown at once, whatever was collected before it.
 */
public final class Resolver {
  /** jq's message for a program with no top-level filter, which is no syntax error (ADR-0032). */
  private static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")";

  /** The IDENTs that are constants (jq's parser), when called without arguments. */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true", JqBool.TRUE, "false", JqBool.FALSE, "null", JqNull.NULL);

  /**
   * The variables jq defines without an {@code as} (jq-ref 1.8.2, observed 2026-11-13: {@code $ENV}, {@code $ARGS} and
   * {@code $JQ_BUILD_CONFIGURATION} run, rc 0), none built yet (spec 7, the design approval's O1): unbound, each is a
   * Stub, not the compile error.
   */
  private static final Set<String> PREDEFINED = Set.of("ENV", "ARGS", "JQ_BUILD_CONFIGURATION");

  /** A key or bound that is passed as a Const argument, so it takes no slot and no Bind. */
  private static final int NO_SLOT = -1;

  /** The slot of a group name its alternative has not stored yet, which reads as a Const null (ADR-0038 ruling 2). */
  private static final int UNSTORED = -2;

  /** The slots taken so far: the parameters', then each Bind's and AltBind's in turn (ADR-0034, ADR-0038). */
  private int slots;

  /** The compile's fold budget, carried from the parser (ADR-0035). */
  private final ConstantKey folds;

  /** The filter's text: it places the error blocks and gives each {@code $__loc__} its line. */
  private final FilterText text;

  /** The names in scope where the lowering is, innermost first. */
  private Scope scope;

  /** The compile errors met so far, in jq's order, and the line and column of the first. */
  private final List<String> blocks = new ArrayList<>();

  private int firstLine;
  private int firstColumn;

  /** A name in scope, its slot or {@link #UNSTORED}, and the names outside it. */
  private record Scope(String name, int slot, Scope outer) {}

  /** A Bind a pattern's match lowers to: its value, its slot, and the part of the pattern it spans. */
  private record Step(Node value, int slot, Source source) {}

  /**
   * A pattern's match so far: its steps in jq's matcher order, and for each name the slot of its first store and of
   * its latest. In an alternative of {@code ?//} each store comes into scope at once, for the key expressions after it.
   */
  private static final class Match {
    final List<Step> steps = new ArrayList<>();
    final Map<String, Integer> first = new LinkedHashMap<>();
    final Map<String, Integer> latest = new HashMap<>();
    final boolean alternative;

    Match(boolean alternative) {
      this.alternative = alternative;
    }
  }

  private Resolver(ConstantKey folds, FilterText text) {
    this.folds = folds;
    this.text = text;
  }

  /**
   * The IR of {@code program}, which declares no host variable.
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, jq's error for each
   *     variable not defined, or a Stub
   */
  public static Node resolve(Program program) {
    return resolve(program, List.of());
  }

  /**
   * The IR of {@code program} with the host variables {@code parameters}, each in scope in the whole filter, in the
   * parameter slot of its place in the list (ADR-0038: the engine passes the declared names sorted).
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, jq's error for each
   *     variable not defined, or a Stub
   */
  public static Node resolve(Program program, List<String> parameters) {
    if (program.main() == null) {
      throw new JqCompileException(List.of(NOT_GIVEN), 1, 1);
    }
    if (program.module() != null || !program.imports().isEmpty()) {
      throw Stub.unbuilt("the module directive and imports");
    }
    Resolver resolver = new Resolver(program.folds(), program.text());
    for (String name : parameters) {
      resolver.scope = new Scope(name, resolver.slots++, resolver.scope);
    }
    Node root = resolver.lower(program.main());
    if (!resolver.blocks.isEmpty()) {
      throw new JqCompileException(resolver.blocks, resolver.firstLine, resolver.firstColumn);
    }
    return root;
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
      case Ast.StringField f -> stringIndex(f);
      case Ast.Index i -> index(i);
      case Ast.Each e -> bracket(e.target(), e.source(), e.optional() ? Iterate.QUIET : Iterate.PLAIN, List.of());
      case Ast.Slice s -> slice(s);
      case Ast.Num n -> new Const(n.source(), LiteralNumber.ofLexeme(n.lexeme()));
      case Ast.Str s -> string(s);
      case Ast.Var v -> variable(v.name(), v.source(), v.source());
      case Ast.Loc l -> location(l.source());
      case Ast.Bind b -> binding(b);
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

  /**
   * {@code t."a\(f)"}: {@code t[k]} with the string as its key (jq's {@code gen_index}), the key outside the target, so
   * a quiet one is the quiet index.
   */
  private Node stringIndex(Ast.StringField f) {
    Node key = lower(f.name());
    int slot = slotFor(key);
    Operation op = f.optional() ? Index.QUIET : Index.PLAIN;
    return bind(key, slot, after(f.target(), new Intrinsic(f.source(), op, List.of(argument(key, slot)))));
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
   * operand is the outer loop, as jq's {@code gen_binop} runs it, an Identity, Const or Var operand passed as an
   * argument without a Bind (ADR-0034 amendment for #65, ADR-0037, ADR-0038). The left operand is lowered first, so
   * its unbound variables come first, as jq reports them (HostVariablesTest: {@code $x + $y}). A comparison is lowered
   * so even when both sides are constants (design approval O6 of #66).
   */
  private Node operator(Ast.Binary b, Operation operation) {
    Node left = lower(b.left());
    Node right = lower(b.right());
    int rightSlot = operandSlot(right);
    int leftSlot = operandSlot(left);
    Intrinsic op = new Intrinsic(b.source(), operation, List.of(argument(left, leftSlot), argument(right, rightSlot)));
    return bind(right, rightSlot, bind(left, leftSlot, op));
  }

  /** A new slot for an operand, or {@link #NO_SLOT} for an Identity, a Const or a Var, each passed as it is. */
  private int operandSlot(Node operand) {
    return operand instanceof Identity ? NO_SLOT : slotFor(operand);
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

  /** A new slot for a computed key or bound, or {@link #NO_SLOT} for a Const or a Var, which is read as it is. */
  private int slotFor(Node value) {
    return value instanceof Const || value instanceof Var ? NO_SLOT : slots++;
  }

  /** The argument that reads {@code value}: the atomic node itself, or a Var of its slot. */
  private static Node argument(Node value, int slot) {
    return slot == NO_SLOT ? value : new Var(value.source(), slot);
  }

  /** {@code body} with {@code value} bound to {@code slot} around it, or {@code body} alone for an atomic one. */
  private static Node bind(Node value, int slot, Node body) {
    return slot == NO_SLOT ? body : new Bind(body.source(), value, slot, body);
  }

  /**
   * {@code $name} at {@code at}: a Var of the innermost slot that holds the name, or a Const null for a group name its
   * alternative has not stored yet. A name no scope holds is jq's compile error, over {@code error}, and a Const null
   * stands for it, as the compile fails; a variable jq defines itself is a Stub.
   */
  private Node variable(String name, Source at, Source error) {
    for (Scope s = scope; s != null; s = s.outer()) {
      if (s.name().equals(name)) {
        return s.slot() == UNSTORED ? new Const(at, JqNull.NULL) : new Var(at, s.slot());
      }
    }
    if (PREDEFINED.contains(name)) {
      throw Stub.unbuilt("$" + name);
    }
    if (blocks.isEmpty()) {
      firstLine = text.line(error.start());
      firstColumn = text.column(error.start());
    }
    blocks.add(text.block("$" + name + " is not defined", error.start(), error.end()));
    return new Const(at, JqNull.NULL);
  }

  /** {@code $__loc__} at {@code at}: its location, a constant (ADR-0038, jq's {@code gen_loc_object}). */
  private Const location(Source at) {
    return new Const(at, ConstantKey.location(text.line(at.start())));
  }

  /**
   * {@code E as p | body} or {@code E as p1 ?// p2 ... | body} (ADR-0038): {@code E} is lowered in the scope outside,
   * and each of its results is bound to a slot of its own, on which the patterns match.
   */
  private Node binding(Ast.Bind b) {
    Node value = lower(b.value());
    int slot = slots++;
    return b.patterns().size() == 1 ? destructure(b, value, slot) : alternatives(b, value, slot);
  }

  /**
   * One pattern: the value's Bind, then the match's Binds, then the body, in whose scope each name is the slot of its
   * first store (jq-ref, recorded: bind/repeat-array, bind/repeat-object). The match's key expressions see only the
   * scope outside the pattern (bind/key-sees-outer-variable). {@code E as $x | body} is so one Bind.
   */
  private Node destructure(Ast.Bind b, Node value, int slot) {
    Match match = new Match(false);
    match(b.patterns().getFirst(), slot, match);
    Scope outer = scope;
    for (Map.Entry<String, Integer> name : match.first.entrySet()) {
      scope = new Scope(name.getKey(), name.getValue(), scope);
    }
    Node body = lower(b.body());
    scope = outer;
    return new Bind(b.source(), value, slot, nest(match.steps, body));
  }

  /**
   * The {@code ?//} chain, an AltBind: one group slot per name of all its patterns, in the body's scope. Each
   * alternative is its match's Binds around an Identity, the leaf, which emits once per match; while it is lowered,
   * each group name is a Const null until its first store and then the slot of its latest (ruling 2). At each match a
   * group slot takes the slot of the name's latest store, or, for a name the alternative never stores, the slot of one
   * Const null bound around the chain: jq-core never passes -1, since query-core's null is no jq value (§14).
   */
  private Node alternatives(Ast.Bind b, Node value, int slot) {
    Set<String> names = new LinkedHashSet<>();
    for (Ast.Pattern pattern : b.patterns()) {
      names(pattern, names);
    }
    Scope outer = scope;
    List<Alternative> alternatives = new ArrayList<>();
    int nil = NO_SLOT;
    for (Ast.Pattern pattern : b.patterns()) {
      for (String name : names) {
        scope = new Scope(name, UNSTORED, scope);
      }
      Match match = new Match(true);
      match(pattern, slot, match);
      scope = outer;
      List<Integer> from = new ArrayList<>();
      for (String name : names) {
        Integer own = match.latest.get(name);
        if (own == null) {
          if (nil == NO_SLOT) {
            nil = slots++;
          }
          own = nil;
        }
        from.add(own);
      }
      alternatives.add(new Alternative(nest(match.steps, new Identity(pattern.source())), from));
    }
    List<Integer> groups = new ArrayList<>();
    for (String name : names) {
      groups.add(slots);
      scope = new Scope(name, slots++, scope);
    }
    Node body = lower(b.body());
    scope = outer;
    Node chain = new AltBind(b.source(), value, slot, alternatives, groups, body);
    return nil == NO_SLOT ? chain : new Bind(b.source(), new Const(b.source(), JqNull.NULL), nil, chain);
  }

  /** The names {@code pattern} stores, added to {@code names} in source order. */
  private static void names(Ast.Pattern pattern, Set<String> names) {
    switch (pattern) {
      case Ast.VarPattern v -> names.add(v.name());
      case Ast.ArrayPattern a -> {
        for (Ast.Pattern element : a.elements()) {
          names(element, names);
        }
      }
      case Ast.ObjectPattern o -> {
        for (Ast.ObjPat entry : o.entries()) {
          if (entry.key() instanceof Ast.VarKey k) {
            names.add(k.name());
          }
          if (entry.value() != null) {
            names(entry.value(), names);
          }
        }
      }
    }
  }

  /**
   * The match of {@code pattern} on the value in {@code level}, its steps appended in jq's matcher order (ADR-0038):
   * a name stores the level's slot; an array's elements, last first, each the level's {@code .[i]}; an object's
   * entries in source order, each its key, run on the level's value, then the level's {@code .[key]}, which {@code
   * $name} stores before its own pattern matches it. Each step is jq's non-quiet index, so an absent key or index and a
   * null give null and any other value raises.
   */
  private void match(Ast.Pattern pattern, int level, Match match) {
    switch (pattern) {
      case Ast.VarPattern v -> store(v.name(), level, match);
      case Ast.ArrayPattern a -> {
        for (int i = a.elements().size() - 1; i >= 0; i--) {
          Ast.Pattern element = a.elements().get(i);
          Const at = new Const(element.source(), LiteralNumber.ofLexeme(Integer.toString(i)));
          match(element, step(index(level, at, element.source()), element.source(), match), match);
        }
      }
      case Ast.ObjectPattern o -> {
        for (Ast.ObjPat entry : o.entries()) {
          Node key = key(entry.key());
          Node read = key instanceof Const || key instanceof Var ? key : new Var(key.source(), step(pipe(new Var(key.source(), level), key), entry.source(), match));
          int value = step(index(level, read, entry.source()), entry.source(), match);
          if (entry.key() instanceof Ast.VarKey k) {
            store(k.name(), value, match);
          }
          if (entry.value() != null) {
            match(entry.value(), value, match);
          }
        }
      }
    }
  }

  /** {@code name} stored in {@code slot}: in an alternative it comes into scope at once (ADR-0038). */
  private void store(String name, int slot, Match match) {
    match.first.putIfAbsent(name, slot);
    match.latest.put(name, slot);
    if (match.alternative) {
      scope = new Scope(name, slot, scope);
    }
  }

  /** A new step of {@code match} that binds {@code value}; its slot. */
  private int step(Node value, Source source, Match match) {
    int slot = slots++;
    match.steps.add(new Step(value, slot, source));
    return slot;
  }

  /** The level's value indexed by the atomic {@code key}, jq's non-quiet {@code INDEX}. */
  private static Node index(int level, Node key, Source source) {
    return pipe(new Var(source, level), new Intrinsic(source, Index.PLAIN, List.of(key)));
  }

  /** {@code body} inside the Binds of {@code steps}, the first outermost. */
  private static Node nest(List<Step> steps, Node body) {
    for (int i = steps.size() - 1; i >= 0; i--) {
      Step step = steps.get(i);
      body = new Bind(step.source(), step.value(), step.slot(), body);
    }
    return body;
  }

  /**
   * A string literal: without interpolation a Const of its text, whatever its format (jq-ref 1.8.2, observed
   * 2026-11-13: {@code @base64 "k"} is {@code "k"}); with one, an Intrinsic of Interpolate on its parts, each text part
   * a Const and each interpolation bound around it unless atomic, the last part outermost, as jq loops (ADR-0038,
   * jq-ref: {@code "\(1,2)\(3,4)"} gives 13, 23, 14, 24). Only {@code @text} or no format is built with interpolations.
   */
  private Node string(Ast.Str s) {
    if (plain(s)) {
      return new Const(s.source(), BuiltString.of(bytes(s)));
    }
    if (s.format() != null && !s.format().equals("text")) {
      throw Stub.unbuilt("the @" + s.format() + " string at byte " + s.source().start());
    }
    List<Ast.Part> parts = s.parts();
    Node[] nodes = new Node[parts.size()];
    int[] partSlots = new int[parts.size()];
    List<Node> args = new ArrayList<>(parts.size());
    for (int i = 0; i < parts.size(); i++) {
      nodes[i] =
          switch (parts.get(i)) {
            case Ast.Text t -> new Const(t.source(), BuiltString.of(t.bytes()));
            case Ast.Interp p -> lower(p.query());
          };
      partSlots[i] = operandSlot(nodes[i]);
      args.add(argument(nodes[i], partSlots[i]));
    }
    Node body = new Intrinsic(s.source(), Interpolate.OP, args);
    for (int i = 0; i < parts.size(); i++) {
      body = bind(nodes[i], partSlots[i], body);
    }
    return body;
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
   * constant string key and an Identity, Const or Var value passed as arguments without a Bind. Any other key, a
   * variable's or a Const null standing for an unstored group name among them, is bound and checked once its pair has
   * its value, before the next pair runs. A site whose keys are all constant strings builds on its shape, the distinct
   * keys in their first place, with one argument per key, the value of its last pair; an earlier pair of a repeated key
   * still runs, for its results. Any other site passes a key and a value per pair. The key of {@code {$x: v}} is the
   * variable's value (jq-ref, recorded: bind/object-var-key); a shorthand is {@link #shorthand}.
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
      // jq's error for an unbound $x of {$x: v} spans the whole pair (jq-ref 1.8.2, observed 2026-11-13).
      keys[i] = pair.key() instanceof Ast.VarKey k && pair.value() != null ? variable(k.name(), k.source(), pair.source()) : key(pair.key());
      keySlots[i] = keys[i] instanceof Const c && c.value() instanceof JqString ? NO_SLOT : slots++;
      values[i] = pair.value() == null ? shorthand(pair.key(), keys[i], keySlots[i]) : lower(pair.value());
      valueSlots[i] = operandSlot(values[i]);
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
   * A key that is no {@code $x: v}: a name, the {@code $name} of a shorthand or a pattern, and {@code $__loc__} are
   * constant strings; a string or {@code (query)} is lowered, so a plain string is a Const, which the parser found a
   * string (ADR-0036).
   */
  private Node key(Ast.Key key) {
    return switch (key) {
      case Ast.NameKey k -> new Const(k.source(), BuiltString.of(k.name()));
      case Ast.VarKey k -> new Const(k.source(), BuiltString.of(k.name()));
      case Ast.LocKey k -> new Const(k.source(), BuiltString.of("__loc__"));
      case Ast.StringKey k -> lower(k.string());
      case Ast.QueryKey k -> lower(k.query());
    };
  }

  /**
   * The value of a shorthand pair, whose key {@link #key} lowered to {@code name}, bound to {@code slot} when computed:
   * a name or a constant string is the field of its key; {@code $x} the variable; {@code $__loc__} its location; a
   * string with an interpolation the input's {@code .[k]} of the key's own result, so the key runs once (jq-ref 1.8.2,
   * observed 2026-11-13: {@code {"\("a","b")"}} on {@code {"a":1,"b":2}} gives {@code {"a":1}} and {@code {"b":2}}).
   */
  private Node shorthand(Ast.Key key, Node name, int slot) {
    if (key instanceof Ast.NameKey k) {
      return lower(new Ast.Field(k.source(), null, k.name(), false));
    }
    if (key instanceof Ast.VarKey k) {
      return variable(k.name(), k.source(), k.source());
    }
    if (key instanceof Ast.LocKey k) {
      return location(k.source());
    }
    Ast.StringKey k = (Ast.StringKey) key;
    if (name instanceof Const) {
      return lower(new Ast.StringField(k.source(), null, k.string(), false));
    }
    return new Intrinsic(k.source(), Index.PLAIN, List.of(new Var(k.source(), slot)));
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

  /** A string with no interpolation, which is a constant whatever its format. */
  private static boolean plain(Ast.Str s) {
    return s.parts().stream().allMatch(Ast.Text.class::isInstance);
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
