package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Arithmetic;
import io.github.subapavel.jqj.internal.op.BuildObject;
import io.github.subapavel.jqj.internal.op.Empty;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.FieldRun;
import io.github.subapavel.jqj.internal.op.Formats;
import io.github.subapavel.jqj.internal.op.Index;
import io.github.subapavel.jqj.internal.op.Interpolate;
import io.github.subapavel.jqj.internal.op.Iterate;
import io.github.subapavel.jqj.internal.op.JqTruth;
import io.github.subapavel.jqj.internal.op.LoopState;
import io.github.subapavel.jqj.internal.op.ObjectKey;
import io.github.subapavel.jqj.internal.op.Raise;
import io.github.subapavel.jqj.internal.op.Range;
import io.github.subapavel.jqj.internal.op.Slice;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Lowers a parsed filter to IR (GLOSSARY "Resolver", ADR-0032), pass 2 after the {@link Binder} (ADR-0040). Built so
 * far (#33, #34, #61, #63): {@code .}; a field by name or by a plain string, {@code .[k]}, {@code .[a:b]} and {@code
 * .[]}, each with its quiet variant {@code ?} (ADR-0034), and a chain of them and {@code |}, which nest as {@code
 * Pipe(term, Intrinsic)}; {@code ,}; parentheses, which make no node; a number or plain string literal, and {@code
 * true}, {@code false} and {@code null}, each a Const; a bare {@code @text}; {@code empty}; and (#64) {@code [e]}, a
 * Collect, and {@code {k: v, ...}}, its pairs bound key-outer around one construction; and (#65) {@code + - * / %},
 * right operand outer, and {@code -t}, each result of {@code t} negated; and (#66) {@code == != < > <= >=}, right
 * operand outer, {@code and}, {@code or} and {@code not}, each an If, and {@code //}, an Alt, both on jq's truth
 * (ADR-0037); and (#67, ADR-0038) {@code E as p | body}, its pattern lowered to Binds in jq's matcher order, and {@code
 * E as p1 ?// p2 ... | body}, an AltBind; {@code $name}, a Var of its slot, a host variable's a parameter slot; {@code
 * $__loc__}, a Const; and a string with interpolations, an Intrinsic of Interpolate over its parts; and (#68, ADR-0039)
 * {@code if ... elif ... else ... end}, nested Ifs, {@code try}, {@code catch} and a postfix {@code ?} on a term that is
 * no step, a Try, {@code error} and {@code error(f)}, and {@code label $name | ...} and {@code break $name}, a Label
 * and a Break of its slot; and (#69, ADR-0041) {@code reduce} and {@code foreach}, a Reduce or Foreach around its
 * pattern's Binds and the Advance of its state slot, and {@code range(a; b)}, an Intrinsic of Range; and (#70,
 * ADR-0040) {@code def}, which makes no node: a call of a definition is its body, inlined. Every other kind is a Stub.
 *
 * <p>A key or bound that is a Const or a Var is the step's argument; any other is bound around the step's target, the
 * key outside the target and {@code a} outside {@code b} (ADR-0034, jq's {@code gen_index} and {@code
 * gen_slice_index}). A comma chain is lowered without recursion over its length: the parser's left-nested spine is
 * flattened by a loop and built again balanced, so its depth is about log2 of its length, with the same results and
 * ticks (F-32). A chain of postfix steps and a run of definitions are lowered by loops too (F-42, #70), and a run of
 * more than {@link #FIELD_RUN} field reads is one step that reads them in turn ({@link FieldRun}).
 *
 * <p>The Binder has bound every reference and thrown jq's binding errors, so each name found here has its declaration
 * in the environment ({@link Env}): a slot, a closure or a definition's body. A call of a definition lowers its body in
 * the environment the definition was written in, plus its parameters, with fresh slots for each copy; a filter
 * parameter is the caller's closure, lowered in the caller's environment on the callee's input at each use, and a value
 * parameter {@code $a} is also a Bind of it around the body, the first parameter outermost (ADR-0040). Lowering a call
 * of a recursive definition is a Stub until spec 2b. One compile spends at most {@link #INLINE_CAP} nodes inside
 * expansions, and nests at most {@link #EXPANSIONS} - 1 of them; past either the filter is a Stub (GLOSSARY "Inline
 * cap"). A Stub is thrown at once.
 */
public final class Resolver {
  /** jq's message for a program with no top-level filter, which is no syntax error (ADR-0032). */
  private static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")";

  /** ADR-0040: how many nodes one compile lowers inside expansions, definition bodies and closures, at most. */
  static final int INLINE_CAP = 65_536;

  /** ADR-0040: the nesting of expansions that is a Stub, the 512th (#70 red ruling: 511 nested compile). */
  static final int EXPANSIONS = 512;

  /**
   * The longest run of field reads that stays a Pipe chain: the parser's nesting bound, so a run no longer than it is
   * no deeper than the nesting the parser takes; a longer run is one {@link FieldRun} (F-42).
   */
  static final int FIELD_RUN = Parser.MAX_DEPTH;

  /** A key or bound that is passed as a Const argument, so it takes no slot and no Bind. */
  private static final int NO_SLOT = -1;

  /** The slots taken so far: the parameters', then each Bind's and AltBind's in turn (ADR-0034, ADR-0038). */
  private int slots;

  /** The compile's fold budget, carried from the parser (ADR-0035). */
  private final ConstantKey folds;

  /** The filter's text: it gives each {@code $__loc__} its line. */
  private final FilterText text;

  /** What pass 1 bound each reference to. */
  private final Binder binder;

  /** The declarations in scope where the lowering is, innermost first. */
  private Env env;

  /** How many expansions the lowering is inside. */
  private int depth;

  /** The nodes lowered inside expansions so far. */
  private int inlined;

  /**
   * A declaration the Binder bound a reference to, in scope where the lowering is: a variable's, label's or value
   * parameter's slot, and a filter parameter's, value parameter's or definition's closure; then the declarations
   * outside it.
   */
  private record Env(Object declaration, int slot, Closure closure, Env outer) {}

  /**
   * A body and the environment it was written in: a filter argument, lowered in its caller's environment (GLOSSARY
   * "Closure"), or a definition's body, lowered in its definition's.
   */
  private record Closure(Ast body, Env env) {}

  /** A Bind a pattern's match lowers to: its value, its slot, and the part of the pattern it spans. */
  private record Step(Node value, int slot, Source source) {}

  /** A pattern's match so far: its steps in jq's matcher order, and for each name the slot of its latest store. */
  private static final class Match {
    final List<Step> steps = new ArrayList<>();
    final Map<String, Integer> latest = new HashMap<>();
  }

  /**
   * A postfix step of a chain, its keys and bounds lowered: a field read, which a long run of them fuses, or any other
   * step, which wraps the node of its target, null when the step is the chain's start.
   */
  private sealed interface Peeled {}

  /** A field read: its Source, its step's name ({@code .shown}, with {@code ?} when quiet) and its operation. */
  private record Read(Source source, String name, Field field) implements Peeled {}

  /** Any other step: how it wraps its target's node. */

  private record Around(UnaryOperator<Node> wrap) implements Peeled {}

  private Resolver(ConstantKey folds, FilterText text, Binder binder) {
    this.folds = folds;
    this.text = text;
    this.binder = binder;
  }

  /**
   * The IR of {@code program}, which declares no host variable.
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, jq's binding errors, or a
   *     Stub
   */
  public static Node resolve(Program program) {
    return resolve(program, List.of());
  }

  /**
   * The IR of {@code program} with the host variables {@code parameters}, each in scope in the whole filter, in the
   * parameter slot of its place in the list (ADR-0038: the engine passes the declared names sorted).
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, jq's binding errors, or a
   *     Stub
   */
  public static Node resolve(Program program, List<String> parameters) {
    if (program.main() == null) {
      throw new JqCompileException(List.of(NOT_GIVEN), 1, 1);
    }
    if (program.module() != null || !program.imports().isEmpty()) {
      throw Stub.unbuilt("the module directive and imports");
    }
    Binder binder = Binder.bind(program.main(), parameters, program.text());
    Resolver resolver = new Resolver(program.folds(), program.text(), binder);
    for (Object host : binder.hosts()) {
      resolver.env = new Env(host, resolver.slots++, null, resolver.env);
    }
    return resolver.lower(program.main());
  }

  private Node lower(Ast ast) {
    return switch (ast) {
      case Ast.Paren p -> lower(p.body());
      case Ast.Def d -> definitions(d);
      case Ast.Call c -> call(c);
      // Every other kind builds a node of its own, which an expansion pays for (ADR-0040 "Inline cap").
      default -> {
        spend();
        yield built(ast);
      }
    };
  }

  private Node built(Ast ast) {
    return switch (ast) {
      case Ast.Dot d -> new Identity(d.source());
      case Ast.Pipe p -> pipe(lower(p.left()), lower(p.right()));
      case Ast.Comma c -> comma(c);
      case Ast.Field f -> chain(f);
      case Ast.StringField f -> chain(f);
      case Ast.Index i -> chain(i);
      case Ast.Each e -> chain(e);
      case Ast.Slice s -> chain(s);
      case Ast.Optional o -> chain(o);
      case Ast.Num n -> new Const(n.source(), LiteralNumber.ofLexeme(n.lexeme()));
      case Ast.Str s -> string(s);
      case Ast.Var v -> variable(v, v.source());
      case Ast.Loc l -> location(l.source());
      case Ast.Bind b -> binding(b);
      case Ast.Format f when Formats.lookup(f.name()) != null -> new Intrinsic(f.source(), Formats.lookup(f.name()), List.of());
      case Ast.ArrayCons a -> array(a);
      case Ast.ObjectCons o -> object(o);
      case Ast.Binary b when ConstantKey.arithmetic(b.op()) != null -> binary(b);
      case Ast.Binary b when ConstantKey.comparison(b.op()) != null -> operator(b, ConstantKey.comparison(b.op()));
      case Ast.Binary b when b.op() == BinOp.AND -> and(b);
      case Ast.Binary b when b.op() == BinOp.OR -> or(b);
      case Ast.Binary b when b.op() == BinOp.ALTERNATIVE -> new Alt(b.source(), lower(b.left()), lower(b.right()), JqTruth.TRUTHY);
      case Ast.Neg n -> pipe(lower(n.operand()), new Intrinsic(n.source(), Arithmetic.NEGATE, List.of()));
      case Ast.If i -> conditional(i);
      case Ast.Try t -> attempt(t.source(), t.body(), t.handler());
      case Ast.Label l -> label(l);
      case Ast.Break b -> new Break(b.source(), find(binder.binding(b)).slot());
      case Ast.Reduce r -> reduce(r);
      case Ast.Foreach f -> foreach(f);
      // Each other kind, and each guarded one outside its guard, belongs to a later ticket (spec 2a and 2b).
      default -> throw Stub.unbuilt("the " + ast.getClass().getSimpleName() + " at byte " + ast.source().start());
    };
  }

  /** Spends one node of the compile's inline cap when the lowering is inside an expansion; past the cap, a Stub. */
  private void spend() {
    if (depth > 0 && ++inlined > INLINE_CAP) {
      throw Stub.unbuilt("a filter that inlines more than " + INLINE_CAP + " nodes");
    }
  }

  /**
   * The call {@code c}, as the Binder bound it: a parse constant, a native, {@code error/1}, the body of a definition,
   * or a parameter's closure.
   */
  private Node call(Ast.Call c) {
    return switch ((Binder.Callee) binder.binding(c)) {
      case Binder.Constant k -> {
        spend();
        yield new Const(c.source(), k.value());
      }
      case Binder.Native n -> {
        spend();
        yield natively(n, c);
      }
      case Binder.ErrorMessage _ -> {
        spend();
        // builtin.jq's def error(msg): msg|error; inlined, its nodes at the call's Source (ADR-0040, ADR-0042).
        yield new Pipe(c.source(), lower(c.args().getFirst()), new Intrinsic(c.source(), Raise.OP, List.of()));
      }
      case Binder.Defined d -> expand(d.def(), c);
      case Binder.Parameter p -> closure(find(p.param()).closure());
    };
  }

  /** A call of the native {@code n} (ADR-0040's native table, ADR-0041's {@code range/2}). */
  private Node natively(Binder.Native n, Ast.Call c) {
    return switch (n) {
      case EMPTY -> new Intrinsic(c.source(), Empty.OP, List.of());
      case ERROR -> new Intrinsic(c.source(), Raise.OP, List.of());
      case NOT -> not(c.source());
      case RANGE -> range(c);
    };
  }

  /**
   * The call {@code c} of {@code def}, inlined (ADR-0040): its body lowered in its definition's environment plus its
   * parameters, each bound to its argument's closure in the caller's environment, a closure that is a parameter of
   * the caller passed on as it is; each value parameter's closure is also lowered here, on the call's input, and bound
   * to a slot of its own around the body, the first parameter outermost. A recursive definition is a Stub until spec
   * 2b, and so is the expansion nested {@link #EXPANSIONS} deep.
   */
  private Node expand(Ast.FuncDef def, Ast.Call c) {
    if (binder.recursive(def)) {
      throw Stub.unbuilt("the call of the recursive " + def.name() + "/" + def.params().size() + " at byte " + c.source().start());
    }
    if (depth + 1 >= EXPANSIONS) {
      throw Stub.unbuilt("a filter that nests " + EXPANSIONS + " expansions");
    }
    depth++;
    Env caller = env;
    Env inner = find(def).closure().env();
    List<Ast.Param> params = def.params();
    Node[] values = new Node[params.size()];
    int[] valueSlots = new int[params.size()];
    for (int i = 0; i < params.size(); i++) {
      Ast.Param p = params.get(i);
      Closure argument = argument(c.args().get(i));
      valueSlots[i] = NO_SLOT;
      if (p.value()) {
        values[i] = closure(argument);
        valueSlots[i] = slots++;
      }
      inner = new Env(p, valueSlots[i], argument, inner);
    }
    env = inner;
    Node body = lower(def.body());
    env = caller;
    for (int i = params.size() - 1; i >= 0; i--) {
      body = bind(values[i], valueSlots[i], body);
    }
    depth--;
    return body;
  }

  /** The closure of the argument {@code arg} here: the caller's own closure when {@code arg} is a parameter's call. */
  private Closure argument(Ast arg) {
    if (binder.binding(arg) instanceof Binder.Parameter p) {
      return find(p.param()).closure();
    }
    return new Closure(arg, env);
  }

  /** {@code closure}'s body lowered in its environment. */
  private Node closure(Closure closure) {
    Env at = env;
    env = closure.env();
    Node node = lower(closure.body());
    env = at;
    return node;
  }

  /**
   * A run of definitions, {@code def f: ...; def g: ...; body}, by a loop, since the parser nests a long top-level run
   * as deep as it is long: each definition makes no node, only its body's closure in the environment where it is
   * written, which its calls lower (ADR-0040).
   */
  private Node definitions(Ast.Def d) {
    Env outer = env;
    Ast rest = d;
    while (rest instanceof Ast.Def def) {
      env = new Env(def.def(), NO_SLOT, new Closure(def.def().body(), env), env);
      rest = def.body();
    }
    Node node = lower(rest);
    env = outer;
    return node;
  }

  /** The innermost entry of the environment for {@code declaration}, which the Binder found in scope. */
  private Env find(Object declaration) {
    Env e = env;
    while (e.declaration() != declaration) {
      e = e.outer();
    }
    return e;
  }

  /**
   * A chain of postfix steps, by a loop (F-42): first each step from the outermost in, its keys and bounds lowered
   * before its target as jq places them; then the term the chain starts from; then each step around the node so far,
   * from the innermost out. A step without a target stands alone; a bracket step on the leading dot of {@code .[...]}
   * stands alone, spanning the dot too. {@code t."a\(f)"} is {@code t[k]} with the string as its key (jq's {@code
   * gen_index}); a postfix {@code ?} on a term that is no step is a Try with {@code empty} as its handler (ADR-0039).
   * Each step but the outermost, which {@link #lower} paid for, spends a node of the inline cap.
   */
  private Node chain(Ast outermost) {
    ArrayDeque<Peeled> steps = new ArrayDeque<>();
    Ast innermost = outermost;
    Ast node = outermost;
    Ast term = null;
    while (node != null && term == null) {
      Ast target =
          switch (node) {
            case Ast.Field f -> {
              steps.push(new Read(f.source(), "." + f.name() + (f.optional() ? "?" : ""), new Field(f.name(), f.optional())));
              yield f.target();
            }
            case Ast.StringField f when plain(f.name()) -> {
              BuiltString name = BuiltString.of(bytes(f.name()));
              steps.push(new Read(f.source(), "." + name.toJson() + (f.optional() ? "?" : ""), new Field(name.value(), f.optional())));
              yield f.target();
            }
            case Ast.StringField f -> {
              steps.push(keyed(f.source(), f.target(), f.name(), f.optional() ? Index.QUIET : Index.PLAIN, false));
              yield f.target();
            }
            case Ast.Index i -> {
              steps.push(keyed(i.source(), i.target(), i.index(), i.optional() ? Index.QUIET : Index.PLAIN, true));
              yield i.target();
            }
            case Ast.Each e -> {
              Operation op = e.optional() ? Iterate.QUIET : Iterate.PLAIN;
              steps.push(new Around(t -> bracket(e.target(), t, e.source(), op, List.of())));
              yield e.target();
            }
            case Ast.Slice s -> {
              steps.push(slice(s));
              yield s.target();
            }
            case Ast.Optional o -> {
              Source at = new Source(o.target().source().start(), o.source().end());
              steps.push(new Around(t -> new Try(at, t, new Intrinsic(at, Empty.OP, List.of()))));
              yield o.target();
            }
            // The term the chain starts from, which is no step.
            default -> {
              term = node;
              yield null;
            }
          };
      if (term == null) {
        innermost = node;
        node = target;
      }
    }
    for (int i = 1; i < steps.size(); i++) {
      spend();
    }
    // The leading dot of .[...] is the bracket step's own (bracket).
    boolean own = term instanceof Ast.Dot && (innermost instanceof Ast.Index || innermost instanceof Ast.Each || innermost instanceof Ast.Slice);
    Node lowered = term == null || own ? null : lower(term);
    List<Read> run = new ArrayList<>();
    for (Peeled step : steps) {
      if (step instanceof Read read) {
        run.add(read);
      } else {
        lowered = ((Around) step).wrap().apply(reads(lowered, run));
      }
    }
    return reads(lowered, run);
  }

  /**
   * The field reads of {@code run} after {@code target} (null for none), and {@code run} emptied: each a step of its
   * own, or more than {@link #FIELD_RUN} of them one {@link FieldRun}, named and spanning as the reads together.
   */
  private static Node reads(Node target, List<Read> run) {
    Node node = target;
    if (run.size() > FIELD_RUN) {
      StringBuilder name = new StringBuilder();
      List<Field> fields = new ArrayList<>(run.size());
      for (Read read : run) {
        name.append(read.name());
        fields.add(read.field());
      }
      Source span = new Source(run.getFirst().source().start(), run.getLast().source().end());
      node = after(node, new Intrinsic(span, new Operation(name.toString(), new FieldRun(fields)), List.of()));
    } else {
      for (Read read : run) {
        node = after(node, new Intrinsic(read.source(), new Operation(read.name(), read.field()), List.of()));
      }
    }
    run.clear();
    return node;
  }

  /**
   * A step {@code op} of one key, {@code t[k]} (a bracket step when {@code bracketed}) or {@code t."a\(f)"}, the key
   * lowered now and bound outside the target.
   */
  private Around keyed(Source own, Ast target, Ast keyAst, Operation op, boolean bracketed) {
    Node key = lower(keyAst);
    int slot = slotFor(key);
    List<Node> args = List.of(argument(key, slot));
    return new Around(t -> bind(key, slot, bracketed ? bracket(target, t, own, op, args) : after(t, new Intrinsic(own, op, args))));
  }

  /** {@code t[a:b]}: {@code a} outside {@code b} outside the target, lowered now; a missing bound is a Const null. */
  private Around slice(Ast.Slice s) {
    Node from = s.from() == null ? new Const(s.source(), JqNull.NULL) : lower(s.from());
    int fromSlot = slotFor(from);
    Node to = s.to() == null ? new Const(s.source(), JqNull.NULL) : lower(s.to());
    int toSlot = slotFor(to);
    Operation op = s.optional() ? Slice.QUIET : Slice.PLAIN;
    List<Node> args = List.of(argument(from, fromSlot), argument(to, toSlot));
    return new Around(t -> bind(from, fromSlot, bind(to, toSlot, bracket(s.target(), t, s.source(), op, args))));
  }

  /** The step {@code step} on {@code target}'s results, or alone when there is no target. */
  private static Node after(Node target, Intrinsic step) {
    return target == null ? step : pipe(target, step);
  }

  /**
   * The bracket step {@code op} with {@code args} on {@code lowered}, the node of {@code target}, spanning {@code own};
   * with no node, on the leading dot of {@code .[...]}, it is the step alone, spanning the dot too.
   */
  private static Node bracket(Ast target, Node lowered, Source own, Operation op, List<Node> args) {
    if (lowered == null) {
      return new Intrinsic(new Source(target.source().start(), own.end()), op, args);
    }
    return pipe(lowered, new Intrinsic(own, op, args));
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
   * argument without a Bind (ADR-0034 amendment for #65, ADR-0037, ADR-0038). The left operand is lowered first, as
   * the Binder walks it. A comparison is lowered so even when both sides are constants (design approval O6 of #66).
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

  /**
   * {@code if c then a elif c2 then b else d end} (ADR-0039): nested Ifs on jq's truth, an elif the If of its else and a
   * missing else an Identity, lowered in source order.
   */
  private Node conditional(Ast.If i) {
    Node cond = lower(i.cond());
    Node then = lower(i.then());
    Node otherwise = i.otherwise() == null ? new Identity(i.source()) : lower(i.otherwise());
    return when(i.source(), cond, then, otherwise);
  }

  /**
   * {@code try body catch handler} at {@code at}, and {@code try body} with a null handler, which takes {@code empty}
   * (ADR-0039). The body is lowered first. A navigation step's {@code ?} is its quiet variant (ADR-0034), which the
   * parser marks on the step, and a {@code ?} on any other term is a step of its {@link #chain}.
   */
  private Node attempt(Source at, Ast body, Ast handler) {
    Node lowered = lower(body);
    Node caught = handler == null ? new Intrinsic(at, Empty.OP, List.of()) : lower(handler);
    return new Try(at, lowered, caught);
  }

  /**
   * {@code label $name | body} (ADR-0039): a slot of its own for the activation's token, which a {@code break} the
   * Binder bound to this label reads.
   */
  private Node label(Ast.Label l) {
    int slot = slots++;
    Env outer = env;
    env = new Env(l, slot, null, outer);
    Node body = lower(l.body());
    env = outer;
    return new Label(l.source(), slot, body);
  }

  /**
   * {@code reduce SRC as P (I; U)} (ADR-0041): {@code Reduce(I, s, bindP(SRC, Advance(s, U)))}, with a state slot s of
   * its own and {@link #bound}'s Bind or AltBind as bindP; U runs on {@link #state} of the slot's value, and so does
   * what the Reduce emits, so a state an empty update left is jq's null. SRC and I see the scope outside, U the
   * pattern's names too. They are lowered in jq's order, I first, then SRC, P's keys and U.
   */
  private Node reduce(Ast.Reduce r) {
    Node init = lower(r.init());
    int state = slots++;
    Node source = lower(r.input());
    Node each = bound(r.source(), source, r.patterns(), () -> advance(state, r.update()));
    return pipe(new Reduce(r.source(), init, state, each), state(r.source()));
  }

  /**
   * {@code foreach SRC as P (I; U; E)} (ADR-0041): {@code Foreach(I, s, bindP(SRC, Advance(s, U) | E))}, and without
   * an extract {@code Foreach(I, s, bindP(SRC, Advance(s, U)))}; U runs on {@link #state} of the slot's value, and
   * scopes and order are {@link #reduce}'s, E last. Each result is an update's, so none is an empty state.
   */
  private Node foreach(Ast.Foreach f) {
    Node init = lower(f.init());
    int state = slots++;
    Node source = lower(f.input());
    Node each =
        bound(
            f.source(),
            source,
            f.patterns(),
            () -> {
              Node advance = advance(state, f.update());
              return f.extract() == null ? advance : pipe(advance, lower(f.extract()));
            });
    return new Foreach(f.source(), init, state, each);
  }

  /** The Advance of the state slot {@code state} on {@code update}, which runs on the slot's value as jq sees it. */
  private Node advance(int state, Ast update) {
    Source at = update.source();
    return new Advance(at, state, pipe(state(at), lower(update)));
  }

  /** {@link LoopState}: jq's null for the empty state an Advance leaves (founding §14), any other value as it is. */
  private static Intrinsic state(Source at) {
    return new Intrinsic(at, LoopState.OP, List.of());
  }

  /**
   * {@code range(A; B)} (ADR-0041): the call of {@link Range} on its bounds, B bound inside A, so the start is the outer
   * loop, an Identity, Const or Var bound passed as an argument without a Bind, as an operator's operand is (ADR-0034
   * amendment for #65).
   */
  private Node range(Ast.Call c) {
    Node start = lower(c.args().get(0));
    Node end = lower(c.args().get(1));
    int startSlot = operandSlot(start);
    int endSlot = operandSlot(end);
    Intrinsic call = new Intrinsic(c.source(), Range.OP, List.of(argument(start, startSlot), argument(end, endSlot)));
    return bind(start, startSlot, bind(end, endSlot, call));
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
   * The variable {@code reference} at {@code at}, as the Binder bound it: a Var of its declaration's slot, or a Const
   * null for a group name its alternative has not stored yet; a variable jq defines itself is a Stub.
   */
  private Node variable(Object reference, Source at) {
    Object declaration = binder.binding(reference);
    if (declaration == Binder.UNSTORED) {
      return new Const(at, JqNull.NULL);
    }
    if (declaration instanceof Binder.Predefined p) {
      throw Stub.unbuilt("$" + p.name());
    }
    return new Var(at, find(declaration).slot());
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
    return bound(b.source(), value, b.patterns(), () -> lower(b.body()));
  }

  /**
   * {@code value}, lowered already, bound to a slot of its own and taken apart by {@code patterns} around the node
   * {@code body} lowers in the patterns' scope (ADR-0038): a Bind for one pattern, an AltBind for a {@code ?//} chain.
   * {@code E as p | body} and the source and pattern of a {@code reduce} or {@code foreach} (ADR-0041) lower so.
   */
  private Node bound(Source source, Node value, List<Ast.Pattern> patterns, Supplier<Node> body) {
    int slot = slots++;
    return patterns.size() == 1 ? destructure(source, value, slot, patterns.getFirst(), body) : alternatives(source, value, slot, patterns, body);
  }

  /**
   * One pattern: the value's Bind, then the match's Binds, then the body, which reads each name at the slot of its
   * first store, as the Binder bound it. {@code E as $x | body} is so one Bind.
   */
  private Node destructure(Source source, Node value, int slot, Ast.Pattern pattern, Supplier<Node> lowerBody) {
    Env outer = env;
    Match match = new Match();
    match(pattern, slot, match);
    Node body = lowerBody.get();
    env = outer;
    return new Bind(source, value, slot, nest(match.steps, body));
  }

  /**
   * The {@code ?//} chain, an AltBind: one group slot per name of all its patterns, in the body's scope. Each
   * alternative is its match's Binds around an Identity, the leaf, which emits once per match. At each match a group
   * slot takes the slot of the name's latest store, or, for a name the alternative never stores, the slot of one Const
   * null bound around the chain: jq-core never passes -1, since query-core's null is no jq value (§14).
   */
  private Node alternatives(Source source, Node value, int slot, List<Ast.Pattern> patterns, Supplier<Node> lowerBody) {
    Map<String, Object> groups = binder.groups(patterns);
    Env outer = env;
    List<Alternative> alternatives = new ArrayList<>();
    int nil = NO_SLOT;
    for (Ast.Pattern pattern : patterns) {
      Match match = new Match();
      match(pattern, slot, match);
      env = outer;
      List<Integer> from = new ArrayList<>();
      for (String name : groups.keySet()) {
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
    List<Integer> groupSlots = new ArrayList<>();
    for (Object group : groups.values()) {
      groupSlots.add(slots);
      env = new Env(group, slots++, null, env);
    }
    Node body = lowerBody.get();
    env = outer;
    Node chain = new AltBind(source, value, slot, alternatives, groupSlots, body);
    return nil == NO_SLOT ? chain : new Bind(source, new Const(source, JqNull.NULL), nil, chain);
  }

  /**
   * The match of {@code pattern} on the value in {@code level}, its steps appended in jq's matcher order (ADR-0038):
   * a name stores the level's slot; an array's elements, last first, each the level's {@code .[i]}; an object's
   * entries in source order, each its key, run on the level's value, then the level's {@code .[key]}, which {@code
   * $name} stores before its own pattern matches it. Each step is jq's non-quiet index, so an absent key or index and a
   * null give null and any other value raises. Each element spends a node of the inline cap.
   */
  private void match(Ast.Pattern pattern, int level, Match match) {
    spend();
    switch (pattern) {
      case Ast.VarPattern v -> store(v.name(), v, level, match);
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
            store(k.name(), k, value, match);
          }
          if (entry.value() != null) {
            match(entry.value(), value, match);
          }
        }
      }
    }
  }

  /** The store of {@code name}, declared by {@code declaration}, in {@code slot}: in scope at once, as its latest. */
  private void store(String name, Object declaration, int slot, Match match) {
    match.latest.put(name, slot);
    env = new Env(declaration, slot, null, env);
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
   * variable's value (jq-ref, recorded: bind/object-var-key); a shorthand is {@link #shorthand}. Each pair spends a node
   * of the inline cap.
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
      spend();
      Ast.Pair pair = pairs.get(i);
      keys[i] = pair.key() instanceof Ast.VarKey k && pair.value() != null ? variable(k, k.source()) : key(pair.key());
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
      return variable(k, k.source());
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
