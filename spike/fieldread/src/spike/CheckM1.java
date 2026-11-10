// PROTOTYPE — throwaway follow-up for issue #5. Never merged.
package spike;

import java.nio.charset.StandardCharsets;

/** TapeM1 agrees with Tape on the spike documents and handles the edge cases jq-ref showed. */
public final class CheckM1 {
    static String one(String label, byte[] d) {
        try {
            TapeM1 t = new TapeM1(true); t.index(d); int i = t.field(Docs.KEY);
            String v = i < 0 ? "none" : new String(d, t.offsetOf(i), Cursor.skipValue(d, t.offsetOf(i)) - t.offsetOf(i), StandardCharsets.UTF_8);
            return label + ": entries=" + t.n + " rootTag=" + TapeM1.tag(t.t[0]) + " dupObjects=" + t.dupObjects + " target=" + v;
        } catch (RuntimeException e) { return label + ": ERROR " + e.getMessage(); }
    }
    public static void main(String[] a) {
        for (String s : new String[] { "1KB", "100KB", "10MB" }) {
            byte[] d = Docs.make(Docs.parseSize(s), "middle");
            Tape ref = new Tape(1024); ref.index(d);
            System.out.println(one(s + " (Tape entries=" + ref.n + ")", d));
        }
        System.out.println(one("duplicate", "{\"x\":0,\"a\":1,\"b\":2,\"target\":3,\"target\":9}".getBytes()));
        System.out.println(one("dup above 8 keys", "{\"k1\":1,\"k2\":1,\"k3\":1,\"k4\":1,\"k5\":1,\"k6\":1,\"k7\":1,\"k8\":1,\"k9\":1,\"target\":1,\"k3\":2}".getBytes()));
        System.out.println(one("no dup, 12 keys", "{\"k1\":1,\"k2\":1,\"k3\":1,\"k4\":1,\"k5\":1,\"k6\":1,\"k7\":1,\"k8\":1,\"k9\":1,\"k10\":1,\"k11\":1,\"target\":7}".getBytes()));
        System.out.println(one("depth 10000", ("[".repeat(9999) + "{\"target\":1}" + "]".repeat(9999)).getBytes()));
        System.out.println(one("depth 10001", ("[".repeat(10000) + "{\"target\":1}" + "]".repeat(10000)).getBytes()));
        byte[] bad = "{\"target\":\"a\u0000b\"}".getBytes(StandardCharsets.ISO_8859_1); bad[12] = (byte) 0xFF;
        TapeM1 t = new TapeM1(true); t.index(bad);
        System.out.println("invalid UTF-8 string tag=" + TapeM1.tag(t.t[2]) + " (6 = STR_ESC)");
    }
}
