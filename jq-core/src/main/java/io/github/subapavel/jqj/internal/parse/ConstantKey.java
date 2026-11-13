/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): check_object_key and constant_fold in src/parser.y, and the
 * folds its block_is_const sees, gen_const_array and gen_const_object in src/compile.c.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.internal.op.Arithmetic;
import io.github.subapavel.jqj.internal.op.ObjectKey;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.EvaluationException;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The parser-action check of a computed object key (ADR-0036, GLOSSARY "Constant key"): a key jq folds to a constant
 * that is no string is jq's error. jq folds what its grammar builds as one constant (jq-ref 1.8.2, observed
 * 2026-11-13): a number, a string without interpolation (its format applies to none), {@code true}, {@code false} and
 * {@code null}; parentheses; a pipe one side of which is {@code .} alone; an array of constants joined by commas that
 * nest to the left only ({@code [(1,2),3]}, not {@code [1,(2,3)]}); and an object whose keys are names, strings or
 * constant strings and whose values are constants, folded as a construction is (a repeated key keeps its first place
 * and its last value). Nothing else folds: {@code 1|2}, {@code -1}, {@code 1?}, {@code [.]} and a key whose value or
 * key is a shorthand or a variable are known only at run time. {@code $__loc__} folds in jq too; it is not built yet, so
 * it is no constant here. An arithmetic operator on two constants folds to its result (#65, {@link #binary}), which the
 * resolver lowers to a Const too.
 */
final class ConstantKey {
  /** What {@code .} alone folds to: a pipe drops it. */
  private static final Object DOT = new Object();

  /** The calls that are constants, by name and arity (jq's parser). */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true/0", JqBool.TRUE, "false/0", JqBool.FALSE, "null/0", JqNull.NULL);

  /** The arithmetic operators, which jq folds, and their operations. */
  private static final Map<BinOp, Operation> ARITHMETIC =
      Map.of(
          BinOp.ADD, Arithmetic.PLUS,
          BinOp.SUBTRACT, Arithmetic.MINUS,
          BinOp.MULTIPLY, Arithmetic.MULTIPLY,
          BinOp.DIVIDE, Arithmetic.DIVIDE,
          BinOp.MODULO, Arithmetic.MODULO);

  /**
   * The bytes a fold may build (ADR-0035 amendment: no value larger than 4 KiB): a string of 4096 bytes with its fixed
   * charge, so 4096 bytes fold and 4097 do not; any other value is held to the same charges.
   */
  static final long FOLD_BYTES = Charges.STRING + 4096;

  /** The bytes the key-kind check's second fold may build (ADR-0035, #65 standards review): 1 MiB. */
  static final long CHECK_BYTES = 1 << 20;

  /** The bytes each arithmetic fold of this walk may build. */
  private final long budget;

  private ConstantKey(long budget) {
    this.budget = budget;
  }

  /**
   * jq's message for the key {@code query} when it folds to a constant that is no string, or null. jq folds without a
   * cap, so a key whose fold goes past {@link #FOLD_BYTES} and whose result is an array or an object ({@link
   * #container}) is folded again under {@link #CHECK_BYTES}, for the check only: jq-ref gives rc 3 for
   * {@code {(("x"*5000)/"x"):1}}. Past that bound too the key is left to the run, which raises the same text with rc 5
   * (ADR-0035, DEVIATIONS "Huge constant keys"). A key whose result is a string is valid whatever its length, so it is
   * not folded again; the run builds it.
   */
  static String error(Ast query) {
    ConstantKey capped = new ConstantKey(FOLD_BYTES);
    Object key = capped.fold(query);
    if (key == null && capped.container(query)) {
      key = new ConstantKey(CHECK_BYTES).fold(query);
    }
    return key instanceof JqValue value && !(value instanceof JqString) ? ObjectKey.message(value) : null;
  }

  /**
   * Whether a fold of {@code ast} gives an array or an object, told by its shape without the parentheses and the pipes
   * with {@code .} around it ({@link #spine}), which is all a fold past the cap can be told by: a construction; a
   * difference or a split, which build arrays; a {@code +} with a container; a merge {@code *}, whose left operand is
   * an object when the fold succeeds. Any other {@code +} or {@code *} past the cap builds a string, a {@code %} builds
   * nothing, and no other kind folds.
   */
  private boolean container(Ast ast) {
    return switch (spine(ast)) {
      case Ast.ArrayCons _ -> true;
      case Ast.ObjectCons _ -> true;
      case Ast.Binary b when b.op() == BinOp.SUBTRACT || b.op() == BinOp.DIVIDE -> true;
      case Ast.Binary b when b.op() == BinOp.ADD -> container(b.left()) || container(b.right());
      case Ast.Binary b when b.op() == BinOp.MULTIPLY -> isObject(b.left());
      default -> false;
    };
  }

  /** Whether {@code ast} gives an object: a construction of one, a {@code +} with one, or a merge {@code *}. */
  private boolean isObject(Ast ast) {
    return switch (spine(ast)) {
      case Ast.ObjectCons _ -> true;
      case Ast.Binary b when b.op() == BinOp.ADD -> isObject(b.left()) || isObject(b.right());
      case Ast.Binary b when b.op() == BinOp.MULTIPLY -> isObject(b.left());
      default -> false;
    };
  }

  /** The constant {@code ast} folds to, {@link #DOT} for {@code .} alone, or null when it is none. */
  private Object fold(Ast ast) {
    return switch (ast) {
      case Ast.Dot _ -> DOT;
      case Ast.Paren p -> fold(p.body());
      case Ast.Pipe p -> pipe(fold(p.left()), fold(p.right()));
      case Ast.Num n -> LiteralNumber.ofLexeme(n.lexeme());
      case Ast.Str s -> string(s);
      // A call by its name and arity, as jq names a function.
      case Ast.Call c -> CONSTANTS.get(c.name() + "/" + c.args().size());
      case Ast.ArrayCons a -> array(a.body());
      case Ast.ObjectCons o -> object(o.pairs());
      case Ast.Binary b -> binary(b);
      default -> null;
    };
  }

  /** The operation of an arithmetic operator, or null for any other. */
  static Operation arithmetic(BinOp op) {
    return ARITHMETIC.get(op);
  }

  /** The constant {@code ast} folds to, or null when it is none: what the resolver lowers to a Const. */
  static JqValue constant(Ast ast) {
    return new ConstantKey(FOLD_BYTES).fold(ast) instanceof JqValue value ? value : null;
  }

  /**
   * jq's {@code constant_fold} of {@code + - * / %} (GLOSSARY "Fold"): the operator on two constants, computed now as
   * the run would, under this walk's bytes-built budget: {@link #FOLD_BYTES} (ADR-0035 amendment), or {@link
   * #CHECK_BYTES} for a key's second check ({@link #error}). A fold that raises, or that would build past the budget, is none: the run computes
   * it, raising or building and charging there. The other
   * operators fold in jq too; comparison is #66's, and {@code and}, {@code or} and {@code //} are not folded.
   */
  private Object binary(Ast.Binary b) {
    Operation op = ARITHMETIC.get(b.op());
    if (op == null || !(fold(b.left()) instanceof JqValue left) || !(fold(b.right()) instanceof JqValue right)) {
      return null;
    }
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, budget, Long.MAX_VALUE);
    try {
      return ((Fn2) op.form()).apply(state, JqNull.NULL, left, right);
    } catch (EvaluationException e) {
      return null;
    }
  }

  /** A pipe of two folds: the other side when one is {@code .} alone, otherwise none. */
  private static Object pipe(Object left, Object right) {
    if (left == DOT) {
      return right;
    }
    return right == DOT ? left : null;
  }

  /** A string without interpolation: its text parts' bytes, joined; with an interpolation, none. */
  private static Object string(Ast.Str s) {
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    for (Ast.Part part : s.parts()) {
      if (!(part instanceof Ast.Text text)) {
        return null;
      }
      value.writeBytes(text.bytes());
    }
    return BuiltString.of(value.toByteArray());
  }

  /**
   * {@code [body]}: empty without a body; otherwise the constants of a comma chain that nests to the left only, a
   * pipe with {@code .} or parentheses around any part of it, each right-hand term one constant.
   */
  private Object array(Ast body) {
    List<Object> elements = new ArrayList<>();
    Ast chain = body == null ? null : spine(body);
    while (chain instanceof Ast.Comma c) {
      elements.addFirst(fold(c.right()));
      chain = spine(c.left());
    }
    if (chain != null) {
      elements.addFirst(fold(chain));
    }
    for (Object e : elements) {
      if (!(e instanceof JqValue)) {
        return null;
      }
    }
    return new BuiltArray(elements.toArray(), elements.size());
  }

  /** {@code ast} without the parentheses and the pipes with {@code .} around it, as jq's blocks join them away. */
  private Ast spine(Ast ast) {
    for (;;) {
      if (ast instanceof Ast.Paren p) {
        ast = p.body();
      } else if (ast instanceof Ast.Pipe p && fold(p.left()) == DOT) {
        ast = p.right();
      } else if (ast instanceof Ast.Pipe p && fold(p.right()) == DOT) {
        ast = p.left();
      } else {
        return ast;
      }
    }
  }

  /** {@code {pairs}}: each key a constant string and each value a constant, or none. */
  private Object object(List<Ast.Pair> pairs) {
    Object[] entries = new Object[2 * pairs.size()];
    for (int i = 0; i < pairs.size(); i++) {
      Ast.Pair pair = pairs.get(i);
      Object key =
          switch (pair.key()) {
            case Ast.NameKey k -> BuiltString.of(k.name());
            case Ast.StringKey k -> string(k.string());
            case Ast.QueryKey k -> fold(k.query());
            default -> null;
          };
      Object value = pair.value() == null ? null : fold(pair.value());
      if (!(key instanceof JqString) || !(value instanceof JqValue)) {
        return null;
      }
      entries[2 * i] = key;
      entries[2 * i + 1] = value;
    }
    return BuiltObject.ofPairs(entries);
  }
}
