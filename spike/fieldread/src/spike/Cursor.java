// PROTOTYPE — throwaway spike for issue #4 (Spike B). Never merged.
package spike;

import java.util.Arrays;

/**
 * Variants "skip" and "stop": a forward cursor over the root object, no tape. Values that are not
 * the field are stepped over by bracket matching (strings tracked, nothing else validated).
 * skip: reads every root key to the closing '}', the last match wins (jq's duplicate rule).
 * stop: returns at the first match; the rest of the document is never read.
 * Both return the value's start offset, or -1. Malformed input is mostly NOT detected.
 */
public final class Cursor {
    private Cursor() {}

    static int ws(byte[] d, int p) { while (p < d.length) { byte b = d[p]; if (b == ' ' || b == '\n' || b == '\r' || b == '\t') p++; else break; } return p; }

    /** p at the opening quote; returns the index after the closing quote. */
    static int skipString(byte[] d, int p) {
        p++;
        while (p < d.length) { byte b = d[p]; if (b == '"') return p + 1; p += b == '\\' ? 2 : 1; }
        throw new Tape.ParseError("unfinished string", p);
    }

    static int skipValue(byte[] d, int p) {
        byte b = d[p];
        if (b == '"') return skipString(d, p);
        if (b == '{' || b == '[') {
            int depth = 0;
            while (p < d.length) {
                b = d[p];
                if (b == '"') { p = skipString(d, p); continue; }
                if (b == '{' || b == '[') depth++;
                else if ((b == '}' || b == ']') && --depth == 0) return p + 1;
                p++;
            }
            throw new Tape.ParseError("unfinished container", p);
        }
        while (p < d.length) { b = d[p]; if (b == ',' || b == '}' || b == ']' || b == ' ' || b == '\n' || b == '\r' || b == '\t') break; p++; }
        return p;
    }

    public static int field(byte[] d, byte[] key, boolean stopAtFirst) {
        int p = ws(d, 0);
        if (p >= d.length || d[p] != '{') return -1;
        p = ws(d, p + 1);
        if (p < d.length && d[p] == '}') return -1;
        int found = -1;
        while (true) {
            if (p >= d.length || d[p] != '"') throw new Tape.ParseError("expected key", p);
            int ks = p + 1, ke = skipString(d, p) - 1;
            p = ws(d, ke + 1);
            if (p >= d.length || d[p] != ':') throw new Tape.ParseError("expected ':'", p);
            p = ws(d, p + 1);
            if (ke - ks == key.length && Arrays.equals(d, ks, ke, key, 0, key.length)) { if (stopAtFirst) return p; found = p; }
            p = ws(d, skipValue(d, p));
            if (p >= d.length) throw new Tape.ParseError("unexpected end", p);
            if (d[p] == ',') { p = ws(d, p + 1); continue; }
            if (d[p] == '}') return found;
            throw new Tape.ParseError("expected ',' or '}'", p);
        }
    }
}
