// PROTOTYPE — throwaway spike for issue #3 (Spike A). Never merged; not production code.
//
// A tiny push-style tree evaluator, just enough for the four filters in ../filters/*.jq,
// built by hand as ASTs below. Variants (one per JVM, -Dvariant=...):
//   stack      recursion on the Java stack of the calling thread (default -Xss 1 MB)
//   tailloop   'stack' plus: a call in tail position whose left context yields exactly one
//              value returns a Next to the caller's call loop (a jump), self or mutual
//   tramp      continuation-passing evaluator on a trampoline: continuations live on the heap;
//              -Dbounce=K runs up to K direct steps before returning to the driver (0 = pure)
//   bigstack   'stack' run on a platform thread with -Dstack=<bytes>
//   virtual    'stack' run on a virtual thread
import java.util.*;
import java.util.function.*;

public final class Spike {

    // ---------- values: Long, Boolean, Object[] (arrays) ----------
    static boolean truthy(Object v) { return v != null && v != Boolean.FALSE; }

    // ---------- environment: one linked list for variables, functions and closure params ----------
    static final class Env {
        final Env parent; Object slot;
        Env(Env parent, Object slot) { this.parent = parent; this.slot = slot; }
        Object at(int depth) { Env e = this; for (int i = 0; i < depth; i++) e = e.parent; return e.slot; }
    }
    record FnVal(FuncDef def, Env self) {}           // self: the Env node whose slot is this FnVal
    record Closure(Node body, Env env) {}

    interface Out { boolean emit(Object v); }

    // ---------- trampoline protocol ----------
    interface Bounce { Bounce step(); }
    interface K { Bounce value(Object v, Bounce more); }
    static final int BOUNCE = Integer.getInteger("bounce", 0);
    static int depth;                                // direct steps since the driver last ran
    static Bounce bounce(Bounce t) { return depth++ < BOUNCE ? t.step() : t; }
    static void drive(Bounce b) { while (b != null) { depth = 0; b = b.step(); } }

    // ---------- tail-loop protocol ----------
    static final boolean TAILLOOP = "tailloop".equals(System.getProperty("variant"));
    static final Object CONT = new Object(), STOP = new Object();
    record Next(FnVal fn, Env env, Object in) {}

    // ---------- AST ----------
    static abstract class Node {
        boolean single() { return false; }
        Object eval1(Env env, Object in) { throw new IllegalStateException("not single: " + this); }
        abstract boolean run(Env env, Object in, Out out);
        /** Tail-capable run: returns CONT, STOP or a Next for the enclosing call loop. */
        Object runTail(Env env, Object in, Out out) { return run(env, in, out) ? CONT : STOP; }
        abstract Bounce cps(Env env, Object in, K k, Bounce done);
        void resolve(Scope s, boolean tail, String fn) {}
    }
    static abstract class Single extends Node {
        @Override boolean single() { return true; }
        @Override boolean run(Env env, Object in, Out out) { return out.emit(eval1(env, in)); }
        @Override Bounce cps(Env env, Object in, K k, Bounce done) {
            Object v = eval1(env, in); return bounce(() -> k.value(v, done));
        }
    }
    /** Names in scope, innermost first; index = Env depth. */
    record Scope(String name, Object kind, Scope parent) {
        int find(String n) { int d = 0; for (Scope s = this; s != null; s = s.parent, d++) if (s.name.equals(n)) return d; throw new IllegalArgumentException(n); }
        Object kindOf(String n) { for (Scope s = this; s != null; s = s.parent) if (s.name.equals(n)) return s.kind; throw new IllegalArgumentException(n); }
    }

    static final class Id extends Single { @Override Object eval1(Env e, Object in) { return in; } }
    static final class Lit extends Single { final Object v; Lit(Object v) { this.v = v; } @Override Object eval1(Env e, Object in) { return v; } }
    static final class Var extends Single {
        final String name; int d; Var(String n) { name = n; }
        @Override Object eval1(Env e, Object in) { return e.at(d); }
        @Override void resolve(Scope s, boolean t, String fn) { d = s.find("$" + name); }
    }
    static final class Length extends Single { @Override Object eval1(Env e, Object in) { return (long) ((Object[]) in).length; } }
    static final class Index extends Node {
        final Node target, key; Index(Node t, Node k) { target = t; key = k; }
        @Override boolean single() { return target.single() && key.single(); }
        static Object idx(Object t, Object k) { Object[] a = (Object[]) t; int i = (int) (long) (Long) k; return i < a.length ? a[i] : null; }
        @Override Object eval1(Env e, Object in) { return idx(target.eval1(e, in), key.eval1(e, in)); }
        @Override boolean run(Env e, Object in, Out out) {
            if (single()) return out.emit(eval1(e, in));
            return key.run(e, in, k -> target.run(e, in, t -> out.emit(idx(t, k))));
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) {
            return key.cps(e, in, (kv, m1) -> target.cps(e, in, (tv, m2) -> { Object r = idx(tv, kv); return bounce(() -> k.value(r, m2)); }, m1), done);
        }
        @Override void resolve(Scope s, boolean t, String fn) { target.resolve(s, false, fn); key.resolve(s, false, fn); }
    }
    enum Op { ADD, SUB, EQ, LT }
    static final class Bin extends Node {
        final Op op; final Node l, r; Bin(Op op, Node l, Node r) { this.op = op; this.l = l; this.r = r; }
        static Object apply(Op op, Object a, Object b) {
            return switch (op) {
                case ADD -> (Long) a + (Long) b;
                case SUB -> (Long) a - (Long) b;
                case EQ -> Objects.equals(a, b);
                case LT -> (Long) a < (Long) b;
            };
        }
        @Override boolean single() { return l.single() && r.single(); }
        @Override Object eval1(Env e, Object in) { return apply(op, l.eval1(e, in), r.eval1(e, in)); }
        @Override boolean run(Env e, Object in, Out out) {
            if (single()) return out.emit(eval1(e, in));
            if (l.single()) { Object lv = l.eval1(e, in); return r.run(e, in, rv -> out.emit(apply(op, lv, rv))); }
            // jq order: right side is the outer loop
            return r.run(e, in, rv -> l.run(e, in, lv -> out.emit(apply(op, lv, rv))));
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) {
            return r.cps(e, in, (rv, m1) -> l.cps(e, in, (lv, m2) -> { Object v = apply(op, lv, rv); return bounce(() -> k.value(v, m2)); }, m1), done);
        }
        @Override void resolve(Scope s, boolean t, String fn) { l.resolve(s, false, fn); r.resolve(s, false, fn); }
    }
    static final class Arr extends Node {
        final Node body; Arr(Node b) { body = b; }
        @Override boolean single() { return true; }
        @Override Object eval1(Env e, Object in) {
            if (body instanceof Comma c && c.a.single() && c.b.single()) return new Object[] { c.a.eval1(e, in), c.b.eval1(e, in) };
            ArrayList<Object> l = new ArrayList<>(); body.run(e, in, v -> { l.add(v); return true; }); return l.toArray();
        }
        @Override boolean run(Env e, Object in, Out out) { return out.emit(eval1(e, in)); }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) {
            ArrayList<Object> l = new ArrayList<>();
            return body.cps(e, in, (v, more) -> { l.add(v); return more; }, () -> { Object a = l.toArray(); return bounce(() -> k.value(a, done)); });
        }
        @Override void resolve(Scope s, boolean t, String fn) { body.resolve(s, false, fn); }
    }
    static final class Empty extends Node {
        @Override boolean run(Env e, Object in, Out out) { return true; }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) { return bounce(done); }
    }
    static final class Comma extends Node {
        final Node a, b; Comma(Node a, Node b) { this.a = a; this.b = b; }
        @Override boolean run(Env e, Object in, Out out) { return a.run(e, in, out) && b.run(e, in, out); }
        @Override Object runTail(Env e, Object in, Out out) { return a.run(e, in, out) ? b.runTail(e, in, out) : STOP; }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) { return a.cps(e, in, k, () -> b.cps(e, in, k, done)); }
        @Override void resolve(Scope s, boolean t, String fn) { a.resolve(s, false, fn); b.resolve(s, t, fn); }
    }
    static final class Pipe extends Node {
        final Node l, r; Pipe(Node l, Node r) { this.l = l; this.r = r; }
        @Override boolean single() { return l.single() && r.single(); }
        @Override Object eval1(Env e, Object in) { return r.eval1(e, l.eval1(e, in)); }
        @Override boolean run(Env e, Object in, Out out) {
            if (l.single()) return r.run(e, l.eval1(e, in), out);
            return l.run(e, in, v -> r.run(e, v, out));
        }
        @Override Object runTail(Env e, Object in, Out out) {
            if (l.single()) return r.runTail(e, l.eval1(e, in), out);
            return run(e, in, out) ? CONT : STOP;
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) { return l.cps(e, in, (v, more) -> r.cps(e, v, k, more), done); }
        // the right side is in tail position only when the left side yields exactly one value
        @Override void resolve(Scope s, boolean t, String fn) { l.resolve(s, false, fn); r.resolve(s, t && l.single(), fn); }
    }
    static final class If extends Node {
        final Node c, t, f; If(Node c, Node t, Node f) { this.c = c; this.t = t; this.f = f; }
        @Override boolean single() { return c.single() && t.single() && f.single(); }
        @Override Object eval1(Env e, Object in) { return truthy(c.eval1(e, in)) ? t.eval1(e, in) : f.eval1(e, in); }
        @Override boolean run(Env e, Object in, Out out) {
            if (c.single()) return (truthy(c.eval1(e, in)) ? t : f).run(e, in, out);
            return c.run(e, in, cv -> (truthy(cv) ? t : f).run(e, in, out));
        }
        @Override Object runTail(Env e, Object in, Out out) {
            if (c.single()) return (truthy(c.eval1(e, in)) ? t : f).runTail(e, in, out);
            return run(e, in, out) ? CONT : STOP;
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) {
            return c.cps(e, in, (cv, more) -> (truthy(cv) ? t : f).cps(e, in, k, more), done);
        }
        @Override void resolve(Scope s, boolean tl, String fn) { c.resolve(s, false, fn); t.resolve(s, tl && c.single(), fn); f.resolve(s, tl && c.single(), fn); }
    }
    /** src as $name | body */
    static final class Bind extends Node {
        final Node src, body; final String name; Bind(Node src, String name, Node body) { this.src = src; this.name = name; this.body = body; }
        @Override boolean run(Env e, Object in, Out out) {
            if (src.single()) return body.run(new Env(e, src.eval1(e, in)), in, out);
            return src.run(e, in, v -> body.run(new Env(e, v), in, out));
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) { return src.cps(e, in, (v, more) -> body.cps(new Env(e, v), in, k, more), done); }
        @Override void resolve(Scope s, boolean t, String fn) { src.resolve(s, false, fn); body.resolve(new Scope("$" + name, "var", s), false, fn); }
    }
    /** reduce src as $name (init; upd), init single */
    static final class Reduce extends Node {
        final Node src, init, upd; final String name;
        Reduce(Node src, String name, Node init, Node upd) { this.src = src; this.name = name; this.init = init; this.upd = upd; }
        @Override boolean run(Env e, Object in, Out out) {
            Object[] acc = { init.eval1(e, in) };
            if (!src.run(e, in, x -> { Env e2 = new Env(e, x); upd.run(e2, acc[0], u -> { acc[0] = u; return true; }); return true; })) return false;
            return out.emit(acc[0]);
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) {
            Object[] acc = { init.eval1(e, in) };
            return src.cps(e, in, (x, more) -> upd.cps(new Env(e, x), acc[0], (u, m2) -> { acc[0] = u; return m2; }, more),
                    () -> { Object v = acc[0]; return bounce(() -> k.value(v, done)); });
        }
        @Override void resolve(Scope s, boolean t, String fn) { src.resolve(s, false, fn); init.resolve(s, false, fn); upd.resolve(new Scope("$" + name, "var", s), false, fn); }
    }
    /** def name(params): body; rest */
    static final class FuncDef extends Node {
        final String name; final String[] params; final Node body, rest;
        FuncDef(String name, String[] params, Node body, Node rest) { this.name = name; this.params = params; this.body = body; this.rest = rest; }
        Env define(Env e) { Env self = new Env(e, null); self.slot = new FnVal(this, self); return self; }
        @Override boolean run(Env e, Object in, Out out) { return rest.run(define(e), in, out); }
        @Override Object runTail(Env e, Object in, Out out) { return rest.runTail(define(e), in, out); }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) { return rest.cps(define(e), in, k, done); }
        @Override void resolve(Scope s, boolean t, String fn) {
            Scope self = new Scope(name, this, s);
            Scope b = self; for (String p : params) b = new Scope(p, "param", b);
            body.resolve(b, true, name);
            rest.resolve(self, t, fn);
        }
    }
    /** name(args) — a defined function or a closure parameter */
    static final class Call extends Node {
        final String name; final Node[] args; int d; boolean param; boolean tail;
        Call(String name, Node... args) { this.name = name; this.args = args; }
        Env bind(Env e, FnVal fv) { Env b = fv.self(); for (Node a : args) b = new Env(b, new Closure(a, e)); return b; }
        @Override boolean run(Env e, Object in, Out out) {
            Object slot = e.at(d);
            if (slot instanceof Closure c) return c.body().run(c.env(), in, out);
            FnVal fv = (FnVal) slot;
            if (!TAILLOOP) return fv.def().body.run(bind(e, fv), in, out);
            Env be = bind(e, fv);
            while (true) {                       // the call loop: tail calls below jump back here
                Object r = fv.def().body.runTail(be, in, out);
                if (r instanceof Next n) { fv = n.fn(); be = n.env(); in = n.in(); continue; }
                return r == CONT;
            }
        }
        @Override Object runTail(Env e, Object in, Out out) {
            if (tail) { FnVal fv = (FnVal) e.at(d); return new Next(fv, bind(e, fv), in); }
            return run(e, in, out) ? CONT : STOP;
        }
        @Override Bounce cps(Env e, Object in, K k, Bounce done) {
            Object slot = e.at(d);
            if (slot instanceof Closure c) return c.body().cps(c.env(), in, k, done);
            FnVal fv = (FnVal) slot; Env be = bind(e, fv);
            return bounce(() -> fv.def().body.cps(be, in, k, done));
        }
        @Override void resolve(Scope s, boolean t, String fn) {
            d = s.find(name); param = "param".equals(s.kindOf(name));
            tail = t && !param;                  // tail position, left context single, a defined function
            for (Node a : args) a.resolve(s, false, fn);
        }
    }

    // ---------- the four filters (keep in step with ../filters/*.jq) ----------
    static Node I(long v) { return new Lit(v); }
    static Node at0() { return new Index(new Id(), I(0)); }
    static Node at1() { return new Index(new Id(), I(1)); }
    /** [.[0] + 1, .[1] + $a[.[0]]] */
    static Node stepPair() { return new Arr(new Comma(new Bin(Op.ADD, at0(), I(1)), new Bin(Op.ADD, at1(), new Index(new Var("a"), at0())))); }
    /** . as $a | ($a|length) as $L | body */
    static Node prelude(Node body) { return new Bind(new Id(), "a", new Bind(new Pipe(new Var("a"), new Length()), "L", body)); }

    static Node filter(String name) {
        Node n = switch (name) {
            case "tail" -> prelude(new FuncDef("s", new String[0],
                    new If(new Bin(Op.EQ, at0(), new Var("L")), at1(), new Pipe(stepPair(), new Call("s"))),
                    new Pipe(new Arr(new Comma(I(0), I(0))), new Call("s"))));
            case "each" -> prelude(new FuncDef("r", new String[] { "f" },
                    new FuncDef("rr", new String[0], new Comma(new Id(), new Pipe(new Call("f"), new Call("rr"))), new Call("rr")),
                    new Reduce(new Pipe(I(0), new Call("r", new If(new Bin(Op.LT, new Id(), new Bin(Op.SUB, new Var("L"), I(1))), new Bin(Op.ADD, new Id(), I(1)), new Empty()))),
                            "i", I(0), new Bin(Op.ADD, new Id(), new Index(new Var("a"), new Var("i"))))));
            case "mutual" -> prelude(new FuncDef("ev", new String[0],
                    new FuncDef("od", new String[0],
                            new If(new Bin(Op.EQ, at0(), new Var("L")), at1(), new Pipe(stepPair(), new Call("ev"))),
                            new If(new Bin(Op.EQ, at0(), new Var("L")), at1(), new Pipe(stepPair(), new Call("od")))),
                    new Pipe(new Arr(new Comma(I(0), I(0))), new Call("ev"))));
            case "nontail" -> prelude(new FuncDef("s", new String[0],
                    new If(new Bin(Op.EQ, new Id(), new Var("L")), I(0), new Bin(Op.ADD, new Index(new Var("a"), new Id()), new Pipe(new Bin(Op.ADD, new Id(), I(1)), new Call("s")))),
                    new Pipe(I(0), new Call("s"))));
            default -> throw new IllegalArgumentException(name);
        };
        n.resolve(null, false, null);
        return n;
    }

    // ---------- running ----------
    static final String VARIANT = System.getProperty("variant", "stack");

    static Object[] input(int n) { Object[] a = new Object[n]; for (int i = 0; i < n; i++) a[i] = (long) i; return a; }

    /** One evaluation; returns the single result or throws. */
    static Object evalOnce(Node f, Object[] in) throws Throwable {
        Callable<Object> task = () -> {
            Object[] res = new Object[1];
            if (VARIANT.equals("tramp")) drive(f.cps(null, in, (v, more) -> { res[0] = v; return more; }, () -> null));
            else f.run(null, in, v -> { res[0] = v; return true; });
            return res[0];
        };
        return switch (VARIANT) {
            case "bigstack" -> onThread(false, task);
            case "virtual" -> onThread(true, task);
            default -> task.call();
        };
    }
    interface Callable<T> { T call() throws Throwable; }
    static Object onThread(boolean virtual, Callable<Object> task) throws Throwable {
        Object[] res = new Object[1]; Throwable[] err = new Throwable[1];
        Runnable r = () -> { try { res[0] = task.call(); } catch (Throwable t) { err[0] = t; } };
        Thread t = virtual ? Thread.ofVirtual().unstarted(r)
                : Thread.ofPlatform().stackSize(Long.getLong("stack", 1L << 30)).unstarted(r);
        t.start(); t.join();
        if (err[0] != null) throw err[0];
        return res[0];
    }

    /** "ok <ms>" or "SOE <ms>" (StackOverflowError caught at the evaluation boundary) */
    static String attempt(Node f, int n) {
        Object[] in = input(n);
        long t0 = System.nanoTime();
        try {
            Object r = evalOnce(f, in);
            double ms = (System.nanoTime() - t0) / 1e6;
            long want = (long) n * (n - 1) / 2;
            return (Long.valueOf(want).equals(r) ? "ok " : "WRONG(" + r + ") ") + String.format("%.2f", ms);
        } catch (StackOverflowError e) {
            return "SOE " + String.format("%.2f", (System.nanoTime() - t0) / 1e6);
        } catch (Throwable e) {
            return "ERR(" + e + ")";
        }
    }

    public static void main(String[] args) {
        String mode = args[0];
        Node f = filter(args[1]);
        switch (mode) {
            case "time" -> {                               // time <filter> <n,n,...> <reps>
                int reps = Integer.parseInt(args[3]);
                for (int i = 0; i < 20; i++) attempt(f, 1000);  // warm-up
                for (String s : args[2].split(",")) {
                    int n = Integer.parseInt(s);
                    List<Double> ok = new ArrayList<>(); String fail = null;
                    for (int i = 0; i < reps; i++) {
                        String r = attempt(f, n);
                        if (r.startsWith("ok ")) ok.add(Double.parseDouble(r.substring(3))); else { fail = r; break; }
                    }
                    Collections.sort(ok);
                    System.out.printf("%-9s %-8s n=%8d %s%n", VARIANT + (VARIANT.equals("tramp") ? "(" + BOUNCE + ")" : ""), args[1], n,
                            fail != null ? fail + " ms (clean: caught, JVM continues)" : String.format("median %.2f ms (min %.2f, max %.2f, %d reps)", ok.get(ok.size() / 2), ok.get(0), ok.get(ok.size() - 1), ok.size()));
                }
            }
            case "cold" -> System.out.println(VARIANT + " " + args[1] + " n=" + args[2] + " first run: " + attempt(f, Integer.parseInt(args[2])));
            case "maxdepth" -> {                           // binary search the largest n that works, after warm-up
                for (int i = 0; i < 20; i++) attempt(f, 1000);
                int lo = 1, hi = Integer.parseInt(args[2]);
                if (attempt(f, hi).startsWith("ok")) { System.out.println(VARIANT + " " + args[1] + " maxdepth >= " + hi); return; }
                while (hi - lo > Math.max(1, lo / 100)) { int mid = (lo + hi) >>> 1; if (attempt(f, mid).startsWith("ok")) lo = mid; else hi = mid; }
                System.out.println(VARIANT + " " + args[1] + " maxdepth ~ " + lo + " (+-1%) " + System.getProperty("stack", ""));
            }
            default -> throw new IllegalArgumentException(mode);
        }
    }
}
