// PROTOTYPE — throwaway spike for issue #4 (Spike B). Never merged.
package spike;

import java.util.Arrays;

/**
 * Variant "tape": one validating pass over the whole document fills a long[] tape, then the field
 * is looked up by walking the root object's entries. Entry: tag (4 bits) | span (28) | offset (32).
 * span = byte length for scalars, number of entries inside for containers (skip = one jump).
 * Not checked: UTF-8 validity, nesting-depth limit.
 */
public final class Tape {
    static final long NULL = 1, FALSE = 2, TRUE = 3, NUM = 4, STR = 5, STR_ESC = 6, ARR = 7, OBJ = 8;

    public long[] t;
    int n;
    byte[] d;
    int p;

    public Tape(int initial) { t = new long[initial]; }

    static long entry(long tag, int span, int off) { return tag << 60 | ((long) span & 0xFFFFFFF) << 32 | (off & 0xFFFFFFFFL); }
    static int tag(long e) { return (int) (e >>> 60); }
    static int span(long e) { return (int) (e >>> 32) & 0xFFFFFFF; }
    static int off(long e) { return (int) e; }

    void add(long e) { if (noTape) return; if (n == t.length) t = Arrays.copyOf(t, n * 2); t[n++] = e; }

    // ---- variant "validate": the same validating pass, no tape writes; remembers the root field ----
    boolean noTape; int depth; byte[] want; int found; boolean takeNext;

    /** Validate the whole document without a tape; returns the last root field's value offset or -1. */
    public int validateAndFind(byte[] doc, byte[] key) {
        noTape = true; want = key; found = -1; depth = 0; takeNext = false;
        try { index(doc); } finally { noTape = false; }
        return found;
    }

    public static final class ParseError extends RuntimeException {
        ParseError(String m, int at) { super(m + " at byte " + at, null, false, false); }
    }

    /** Index the whole document; throws ParseError on invalid JSON. */
    public void index(byte[] doc) {
        d = doc; p = 0; n = 0;
        ws(); value(); ws();
        if (p != d.length) throw new ParseError("trailing bytes", p);
    }

    void ws() { while (p < d.length) { byte b = d[p]; if (b == ' ' || b == '\n' || b == '\r' || b == '\t') p++; else break; } }

    void value() {
        if (p >= d.length) throw new ParseError("unexpected end", p);
        if (takeNext) { takeNext = false; found = p; }
        switch (d[p]) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> lit("true", TRUE);
            case 'f' -> lit("false", FALSE);
            case 'n' -> lit("null", NULL);
            default -> number();
        }
    }

    void object() {
        int me = n, start = p; add(0); p++; ws(); depth++;
        if (p < d.length && d[p] == '}') { p++; } else {
            while (true) {
                ws(); if (p >= d.length || d[p] != '"') throw new ParseError("expected key", p);
                int ks = p; string();
                if (noTape && depth == 1 && p - ks == want.length + 2 && Arrays.equals(d, ks + 1, p - 1, want, 0, want.length)) takeNext = true;
                ws();
                if (p >= d.length || d[p] != ':') throw new ParseError("expected ':'", p);
                p++; ws(); value(); ws();
                if (p >= d.length) throw new ParseError("unexpected end", p);
                if (d[p] == ',') { p++; continue; }
                if (d[p] == '}') { p++; break; }
                throw new ParseError("expected ',' or '}'", p);
            }
        }
        depth--;
        if (!noTape) t[me] = entry(OBJ, n - me - 1, start);
    }

    void array() {
        int me = n, start = p; add(0); p++; ws();
        if (p < d.length && d[p] == ']') { p++; } else {
            while (true) {
                ws(); value(); ws();
                if (p >= d.length) throw new ParseError("unexpected end", p);
                if (d[p] == ',') { p++; continue; }
                if (d[p] == ']') { p++; break; }
                throw new ParseError("expected ',' or ']'", p);
            }
        }
        if (!noTape) t[me] = entry(ARR, n - me - 1, start);
    }

    void string() {
        int start = p++; boolean esc = false;
        while (true) {
            if (p >= d.length) throw new ParseError("unfinished string", start);
            byte b = d[p];
            if (b == '"') { p++; break; }
            if (b == '\\') {
                esc = true;
                if (p + 1 >= d.length) throw new ParseError("unfinished escape", p);
                switch (d[p + 1]) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> p += 2;
                    case 'u' -> { for (int i = 2; i < 6; i++) if (p + i >= d.length || Character.digit(d[p + i], 16) < 0) throw new ParseError("bad \\u", p); p += 6; }
                    default -> throw new ParseError("bad escape", p);
                }
                continue;
            }
            if ((b & 0xFF) < 0x20) throw new ParseError("control character in string", p);
            p++;
        }
        add(entry(esc ? STR_ESC : STR, p - start, start));
    }

    void lit(String s, long tag) {
        int start = p;
        for (int i = 0; i < s.length(); i++) if (p >= d.length || d[p++] != s.charAt(i)) throw new ParseError("invalid literal", start);
        add(entry(tag, s.length(), start));
    }

    void number() {
        int start = p;
        if (p < d.length && d[p] == '-') p++;
        if (p < d.length && d[p] == '0') p++;
        else if (p < d.length && d[p] >= '1' && d[p] <= '9') { while (p < d.length && d[p] >= '0' && d[p] <= '9') p++; }
        else throw new ParseError("invalid value", start);
        if (p < d.length && d[p] == '.') { p++; int f = p; while (p < d.length && d[p] >= '0' && d[p] <= '9') p++; if (p == f) throw new ParseError("bad fraction", p); }
        if (p < d.length && (d[p] == 'e' || d[p] == 'E')) {
            p++; if (p < d.length && (d[p] == '+' || d[p] == '-')) p++;
            int e = p; while (p < d.length && d[p] >= '0' && d[p] <= '9') p++; if (p == e) throw new ParseError("bad exponent", p);
        }
        add(entry(NUM, p - start, start));
    }

    /** The root object's last field with this key (jq: the last duplicate wins): value's tape index, or -1. */
    public int field(byte[] key) {
        long root = t[0];
        if (tag(root) != OBJ) return -1;
        int found = -1, end = 1 + span(root);
        for (int i = 1; i < end; ) {
            long k = t[i];
            if (tag(k) == STR && span(k) == key.length + 2 && Arrays.equals(d, off(k) + 1, off(k) + 1 + key.length, key, 0, key.length)) found = i + 1;
            long v = t[i + 1];
            i += 2 + (tag(v) >= ARR ? span(v) : 0);
        }
        return found;
    }

    public int offsetOf(int idx) { return off(t[idx]); }
}
