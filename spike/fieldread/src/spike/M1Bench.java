// PROTOTYPE — throwaway follow-up for issue #5. Never merged.
package spike;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** The Spike B tape against the milestone-1 reader work, with and without duplicate-key detection. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
public class M1Bench {
    @Param({ "1KB", "100KB", "10MB" }) public String size;
    byte[] doc; Tape spikeB; TapeM1 noDup, dup;

    @Setup public void setup() {
        doc = Docs.make(Docs.parseSize(size), "middle");
        spikeB = new Tape(1024); spikeB.index(doc);
        noDup = new TapeM1(false); noDup.index(doc);
        dup = new TapeM1(true); dup.index(doc);
    }
    @Benchmark public int spikeBTape() { spikeB.index(doc); return spikeB.offsetOf(spikeB.field(Docs.KEY)); }
    @Benchmark public int m1NoDup() { noDup.index(doc); return noDup.offsetOf(noDup.field(Docs.KEY)); }
    @Benchmark public int m1Dup() { dup.index(doc); return dup.offsetOf(dup.field(Docs.KEY)); }
}
