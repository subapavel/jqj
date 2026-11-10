// PROTOTYPE — throwaway spike for issue #4 (Spike B). Never merged.
package spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Writes the documents for jq-ref, checks the variants agree, and shows how each treats edge cases. */
public final class Check {
    static String run(String what, byte[] d) {
        StringBuilder sb = new StringBuilder(what + ":");
        try { Tape t = new Tape(16); t.index(d); int i = t.field(Docs.KEY); sb.append(" tape=").append(i < 0 ? "none" : val(d, t.offsetOf(i))); }
        catch (RuntimeException e) { sb.append(" tape=ERROR(").append(e.getMessage()).append(')'); }
        try { int o = new Tape(16).validateAndFind(d, Docs.KEY); sb.append(" validate=").append(o < 0 ? "none" : val(d, o)); }
        catch (RuntimeException e) { sb.append(" validate=ERROR(").append(e.getMessage()).append(')'); }
        for (boolean stop : new boolean[] { false, true }) {
            try { int o = Cursor.field(d, Docs.KEY, stop); sb.append(stop ? " stop=" : " skip=").append(o < 0 ? "none" : val(d, o)); }
            catch (RuntimeException e) { sb.append(stop ? " stop=" : " skip=").append("ERROR(").append(e.getMessage()).append(')'); }
        }
        return sb.toString();
    }
    static String val(byte[] d, int o) { return new String(d, o, Cursor.skipValue(d, o) - o, StandardCharsets.UTF_8); }

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]); Files.createDirectories(dir);
        for (String s : new String[] { "1KB", "100KB", "10MB" })
            for (String p : new String[] { "start", "middle", "end" }) {
                byte[] d = Docs.make(Docs.parseSize(s), p);
                Files.write(dir.resolve(s + "-" + p + ".json"), d);
                System.out.println(run(s + "-" + p + " (" + d.length + " bytes)", d));
            }
        System.out.println(run("duplicate key", "{\"target\":1,\"b\":{\"x\":2},\"target\":3}".getBytes()));
        System.out.println(run("broken after the field", "{\"target\":1,\"b\":[1,2".getBytes()));
        System.out.println(run("bad literal after the field", "{\"target\":1,\"b\":tru}".getBytes()));
        System.out.println(run("bad literal before the field", "{\"b\":tru,\"target\":1}".getBytes()));
    }
}
