// PROTOTYPE — throwaway spike for issue #4 (Spike B). Never merged.
package spike;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Reading `.target` from one document; every benchmark returns the value's start offset. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
public class FieldReadBench {
    @Param({ "1KB", "100KB", "10MB" }) public String size;
    @Param({ "start", "middle", "end" }) public String pos;

    byte[] doc;
    Tape reused;

    @Setup
    public void setup() {
        doc = Docs.make(Docs.parseSize(size), pos);
        reused = new Tape(1024);
        reused.index(doc);                    // grown once, as a Workspace would keep it
    }

    /** Index everything into a tape kept in the workspace, then look the field up. */
    @Benchmark public int tapeReused() { reused.index(doc); return reused.offsetOf(reused.field(Docs.KEY)); }

    /** Same, with a fresh tape per read (starts at 1024 entries, doubles). */
    @Benchmark public int tapeFresh() { Tape t = new Tape(1024); t.index(doc); return t.offsetOf(t.field(Docs.KEY)); }

    /** The same validating pass as the tape, but no tape: remembers the last root match while parsing. */
    @Benchmark public int validateOnly() { return reused.validateAndFind(doc, Docs.KEY); }

    /** Forward cursor over every root key, last match wins, no tape, no validation. */
    @Benchmark public int skipAll() { return Cursor.field(doc, Docs.KEY, false); }

    /** Forward cursor that stops at the first match. */
    @Benchmark public int stopFirst() { return Cursor.field(doc, Docs.KEY, true); }
}
