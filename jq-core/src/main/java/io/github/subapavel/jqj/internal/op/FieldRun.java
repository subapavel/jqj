package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.List;

/**
 * A long run of field reads, {@code .a.b.c...}, as one step that applies them in turn by a loop (F-42): a Pipe chain of
 * them runs one Java frame deeper per read, which a chain past jq's own bytecode limit does not fit in a 512 KiB stack
 * (DEVIATIONS "Long path chains"). The results, errors and charges are the reads' own: a quiet read that has no result
 * ends the run with none, a read that raises raises, and no read ticks, as none does alone. The loop runs once per read
 * of the filter's text, never per input value.
 */
public final class FieldRun implements Fn0 {
  private final Field[] reads;

  public FieldRun(List<Field> reads) {
    this.reads = reads.toArray(Field[]::new);
  }

  @Override
  public Object apply(RunState state, Object in) {
    Object v = in;
    for (Field read : reads) {
      v = read.apply(state, v);
      if (v == CallForm.NONE) {
        return v;
      }
    }
    return v;
  }
}
