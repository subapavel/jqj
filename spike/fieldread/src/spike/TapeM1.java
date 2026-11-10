// PROTOTYPE — throwaway follow-up for issue #5 (value model and tape layout). Never merged.
package spike;

import java.util.Arrays;

/**
 * The tape as settled in #5 round 1, with the reader work milestone 1 needs: one long per value
 * (tag 4 | span 28 | offset 32), keys as entries, a container's offset at its closing bracket,
 * UTF-8 classified (invalid UTF-8 or DEL → STR_ESC, as jq rewrites them), depth limit 10000,
 * and optional duplicate-key detection (OBJ_DUP): span-filtered linear compares up to 8 keys,
 * a reusable open-addressing table per nesting level above that.
 */
public final class TapeM1 {
    static final long NULL = 1, FALSE = 2, TRUE = 3, NUM = 4, STR = 5, STR_ESC = 6, ARR = 8, OBJ = 9, OBJ_DUP = 10;
    static final int MAX_DEPTH = 10000, LINEAR = 8;

    public long[] t = new long[1024];
    public int n;
    byte[] d; int p; int depth;
    final boolean dups;
    public int dupObjects;

    // duplicate detection scratch, reused across reads: key entry indices of the open objects
    int[] keys = new int[256]; int kn;
    int[][] tables = new int[64][]; int[][] stamps = new int[64][]; int stamp;

    public TapeM1(boolean detectDuplicates) { dups = detectDuplicates; }

    static long entry(long tag, int span, int off) { return tag << 60 | ((long) span & 0xFFFFFFF) << 32 | (off & 0xFFFFFFFFL); }
    static int tag(long e) { return (int) (e >>> 60); }
    static int span(long e) { return (int) (e >>> 32) & 0xFFFFFFF; }
    static int off(long e) { return (int) e; }

    void add(long e) { if (n == t.length) t = Arrays.copyOf(t, n * 2); t[n++] = e; }

    public void index(byte[] doc) {
        d = doc; p = 0; n = 0; depth = 0; kn = 0; dupObjects = 0;
        ws(); value(); ws();
        if (p != d.length) throw new Tape.ParseError("trailing bytes", p);
    }

    void ws() { while (p < d.length) { byte b = d[p]; if (b == ' ' || b == '\n' || b == '\r' || b == '\t') p++; else break; } }

    void value() {
        if (p >= d.length) throw new Tape.ParseError("unexpected end", p);
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

    void enter() { if (++depth > MAX_DEPTH) throw new Tape.ParseError("Exceeds depth limit for parsing", p); }

    void object() {
        enter();
        int me = n; add(0); p++; ws();
        int k0 = kn, count = 0; boolean dup = false;
        if (p < d.length && d[p] == '}') { } else {
            while (true) {
                ws(); if (p >= d.length || d[p] != '"') throw new Tape.ParseError("expected key", p);
                int ki = n; string();
                if (dups && !dup) dup = seen(k0, count, ki);
                count++;
                ws(); if (p >= d.length || d[p] != ':') throw new Tape.ParseError("expected ':'", p);
                p++; ws(); value(); ws();
                if (p >= d.length) throw new Tape.ParseError("unexpected end", p);
                if (d[p] == ',') { p++; continue; }
                if (d[p] == '}') break;
                throw new Tape.ParseError("expected ',' or '}'", p);
            }
        }
        kn = k0;
        if (dup) dupObjects++;
        t[me] = entry(dup ? OBJ_DUP : OBJ, n - me - 1, p);   // offset = the closing bracket
        p++; depth--;
    }

    /** Has the key at tape index ki appeared among this object's earlier keys? Records it. */
    boolean seen(int k0, int count, int ki) {
        long ke = t[ki];
        if (count < LINEAR) {
            for (int j = k0; j < k0 + count; j++) if (sameKey(t[keys[j]], ke)) return true;
            if (kn == keys.length) keys = Arrays.copyOf(keys, kn * 2);
            keys[kn++] = ki;
            if (count + 1 == LINEAR) { stamp++; }      // next key switches this object to the table
            return false;
        }
        int lvl = Math.min(depth, tables.length - 1);
        if (count == LINEAR) { ensure(lvl, 64); for (int j = k0; j < k0 + count; j++) insert(lvl, keys[j]); }
        if (count * 2 >= tables[lvl].length) rehash(lvl, k0, count);
        int[] tb = tables[lvl], st = stamps[lvl]; int mask = tb.length - 1;
        for (int h = hash(ke) & mask; ; h = (h + 1) & mask) {
            if (st[h] != stamp) { st[h] = stamp; tb[h] = ki; return false; }
            if (sameKey(t[tb[h]], ke)) return true;
        }
    }
    void ensure(int lvl, int size) { if (tables[lvl] == null || tables[lvl].length < size) { tables[lvl] = new int[size]; stamps[lvl] = new int[size]; } }
    void insert(int lvl, int ki) {
        int[] tb = tables[lvl], st = stamps[lvl]; int mask = tb.length - 1;
        for (int h = hash(t[ki]) & mask; ; h = (h + 1) & mask) if (st[h] != stamp) { st[h] = stamp; tb[h] = ki; return; }
    }
    void rehash(int lvl, int k0, int count) {
        int[] old = tables[lvl], os = stamps[lvl]; int s0 = stamp;
        tables[lvl] = new int[old.length * 2]; stamps[lvl] = new int[old.length * 2]; stamp++;
        for (int i = 0; i < old.length; i++) if (os[i] == s0) insert(lvl, old[i]);
    }
    int hash(long ke) { int h = 0x811C9DC5, o = off(ke), e = o + span(ke); for (int i = o; i < e; i++) h = (h ^ d[i]) * 0x01000193; return h ^ (h >>> 16); }
    boolean sameKey(long a, long b) {   // raw-byte compare; equal-after-decoding for STR_ESC keys is not done in the spike
        int la = span(a); if (la != span(b)) return false;
        return Arrays.equals(d, off(a), off(a) + la, d, off(b), off(b) + la);
    }

    void array() {
        enter();
        int me = n; add(0); p++; ws();
        if (p < d.length && d[p] == ']') { } else {
            while (true) {
                ws(); value(); ws();
                if (p >= d.length) throw new Tape.ParseError("unexpected end", p);
                if (d[p] == ',') { p++; continue; }
                if (d[p] == ']') break;
                throw new Tape.ParseError("expected ',' or ']'", p);
            }
        }
        t[me] = entry(ARR, n - me - 1, p);
        p++; depth--;
    }

    void string() {
        int start = p++; boolean esc = false;
        while (true) {
            if (p >= d.length) throw new Tape.ParseError("unfinished string", start);
            int b = d[p] & 0xFF;
            if (b == '"') { p++; break; }
            if (b == '\\') {
                esc = true;
                if (p + 1 >= d.length) throw new Tape.ParseError("unfinished escape", p);
                switch (d[p + 1]) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> p += 2;
                    case 'u' -> { for (int i = 2; i < 6; i++) if (p + i >= d.length || Character.digit(d[p + i], 16) < 0) throw new Tape.ParseError("bad \\u", p); p += 6; }
                    default -> throw new Tape.ParseError("bad escape", p);
                }
                continue;
            }
            if (b < 0x20) throw new Tape.ParseError("control character in string", p);
            if (b < 0x7F) { p++; continue; }
            if (b == 0x7F) { esc = true; p++; continue; }   // jq prints DEL as \u007f
            int len = utf8(p);
            if (len < 0) { esc = true; p++; } else p += len;  // invalid UTF-8: jq replaces it with U+FFFD
        }
        add(entry(esc ? STR_ESC : STR, p - start, start));
    }

    /** Length of a valid UTF-8 sequence at i, or -1. */
    int utf8(int i) {
        int b = d[i] & 0xFF;
        if (b >= 0xC2 && b <= 0xDF) return cont(i + 1) ? 2 : -1;
        if (b >= 0xE0 && b <= 0xEF) {
            if (i + 2 >= d.length) return -1;
            int c = d[i + 1] & 0xFF;
            if (b == 0xE0 && c < 0xA0 || b == 0xED && c > 0x9F) return -1;   // overlong, surrogate
            return cont(i + 1) && cont(i + 2) ? 3 : -1;
        }
        if (b >= 0xF0 && b <= 0xF4) {
            if (i + 3 >= d.length) return -1;
            int c = d[i + 1] & 0xFF;
            if (b == 0xF0 && c < 0x90 || b == 0xF4 && c > 0x8F) return -1;
            return cont(i + 1) && cont(i + 2) && cont(i + 3) ? 4 : -1;
        }
        return -1;
    }
    boolean cont(int i) { return i < d.length && (d[i] & 0xC0) == 0x80; }

    void lit(String s, long tag) {
        int start = p;
        for (int i = 0; i < s.length(); i++) if (p >= d.length || d[p++] != s.charAt(i)) throw new Tape.ParseError("invalid literal", start);
        add(entry(tag, s.length(), start));
    }

    void number() {
        int start = p;
        if (p < d.length && d[p] == '-') p++;
        if (p < d.length && d[p] == '0') p++;
        else if (p < d.length && d[p] >= '1' && d[p] <= '9') { while (p < d.length && d[p] >= '0' && d[p] <= '9') p++; }
        else throw new Tape.ParseError("invalid value", start);
        if (p < d.length && d[p] == '.') { p++; int f = p; while (p < d.length && d[p] >= '0' && d[p] <= '9') p++; if (p == f) throw new Tape.ParseError("bad fraction", p); }
        if (p < d.length && (d[p] == 'e' || d[p] == 'E')) {
            p++; if (p < d.length && (d[p] == '+' || d[p] == '-')) p++;
            int e = p; while (p < d.length && d[p] >= '0' && d[p] <= '9') p++; if (p == e) throw new Tape.ParseError("bad exponent", p);
        }
        add(entry(NUM, p - start, start));
    }

    /** Root field lookup: with OBJ (no duplicates) it stops at the first match. */
    public int field(byte[] key) {
        long root = t[0];
        int tg = tag(root);
        if (tg != OBJ && tg != OBJ_DUP) return -1;
        int found = -1, end = 1 + span(root);
        for (int i = 1; i < end; ) {
            long k = t[i];
            if (tag(k) == STR && span(k) == key.length + 2 && Arrays.equals(d, off(k) + 1, off(k) + 1 + key.length, key, 0, key.length)) {
                found = i + 1; if (tg == OBJ) return found;
            }
            long v = t[i + 1];
            i += 2 + (v < 0 ? span(v) : 0);
        }
        return found;
    }
    public int offsetOf(int idx) { return off(t[idx]); }
}
