// PROTOTYPE — throwaway spike for issue #4 (Spike B). Never merged.
package spike;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Synthetic message-like documents: a root object of records plus one "target" field. */
public final class Docs {
    public static final byte[] KEY = "target".getBytes(StandardCharsets.UTF_8);
    public static final String VALUE = "\"value-42\"";

    static String record(int i) {
        return "{\"id\":" + (100000 + i) + ",\"name\":\"item \\\"" + i + "\\\" über\",\"tags\":[\"a\",\"b\",\"c\"],"
                + "\"amount\":" + (i % 1000) + "." + (i % 97) + "e1,\"ok\":" + (i % 2 == 0) + ",\"meta\":{\"n\":null,\"x\":[1,2,3],\"s\":\"}]\"}}";
    }

    /** @param bytes approximate size; @param pos start | middle | end */
    public static byte[] make(int bytes, String pos) {
        int per = record(0).length() + 10;
        int k = Math.max(2, bytes / per);
        int at = switch (pos) { case "start" -> 0; case "middle" -> k / 2; case "end" -> k; default -> throw new IllegalArgumentException(pos); };
        StringBuilder sb = new StringBuilder(bytes + 256).append('{');
        for (int i = 0; i <= k; i++) {
            if (i == at) { if (sb.length() > 1) sb.append(','); sb.append("\"target\":").append(VALUE); }
            if (i == k) break;
            if (sb.length() > 1) sb.append(',');
            sb.append(String.format("\"k%06d\":", i)).append(record(i));
        }
        return sb.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    public static int parseSize(String s) {
        return switch (s) { case "1KB" -> 1 << 10; case "100KB" -> 100 << 10; case "10MB" -> 10 << 20; default -> throw new IllegalArgumentException(s); };
    }
}
